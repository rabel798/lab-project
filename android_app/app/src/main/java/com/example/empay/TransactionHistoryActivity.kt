package com.example.empay

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.empay.auth.SessionManager
import com.example.empay.db.AppDatabase
import com.example.empay.db.TransactionEntity
import com.example.empay.network.BankApiClient
import com.example.empay.network.BankRemoteTransaction
import com.example.empay.network.NetworkManager
import com.example.empay.sync.SyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TransactionHistoryActivity : AppCompatActivity() {

    private lateinit var appDatabase: AppDatabase
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var layoutHistoryContainer: LinearLayout
    private lateinit var tvEmptyState: TextView
    private lateinit var tvTotalOfflineExchanged: TextView
    private lateinit var tvQueuedSyncCount: TextView
    private lateinit var tvSyncedCount: TextView

    private lateinit var btnFilterAll: Button
    private lateinit var btnFilterQueued: Button
    private lateinit var btnFilterSynced: Button

    private var allTransactions: List<TransactionEntity> = emptyList()
    private var currentFilter: String = "ALL" // "ALL", "QUEUED", "SYNCED"

    private val timeFormat = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transaction_history)

        appDatabase = AppDatabase.getInstance(this)
        initViews()
        setupListeners()
        loadTransactions()
    }

    private fun initViews() {
        findViewById<ImageButton>(R.id.btnBackHome).setOnClickListener { finish() }

        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
        swipeRefreshLayout.setColorSchemeColors(
            ContextCompat.getColor(this, R.color.primary),
            ContextCompat.getColor(this, R.color.electric_orange)
        )

        layoutHistoryContainer = findViewById(R.id.layoutHistoryContainer)
        tvEmptyState = findViewById(R.id.tvEmptyState)
        tvTotalOfflineExchanged = findViewById(R.id.tvTotalOfflineExchanged)
        tvQueuedSyncCount = findViewById(R.id.tvQueuedSyncCount)
        tvSyncedCount = findViewById(R.id.tvSyncedCount)

        btnFilterAll = findViewById(R.id.btnFilterAll)
        btnFilterQueued = findViewById(R.id.btnFilterQueued)
        btnFilterSynced = findViewById(R.id.btnFilterSynced)
    }

    private fun setupListeners() {
        swipeRefreshLayout.setOnRefreshListener {
            performRefreshAndSync()
        }

        btnFilterAll.setOnClickListener {
            currentFilter = "ALL"
            updateFilterTabs()
            renderTransactions()
        }

        btnFilterQueued.setOnClickListener {
            currentFilter = "QUEUED"
            updateFilterTabs()
            renderTransactions()
        }

        btnFilterSynced.setOnClickListener {
            currentFilter = "SYNCED"
            updateFilterTabs()
            renderTransactions()
        }
    }

    private fun updateFilterTabs() {
        val activeBg = ContextCompat.getColorStateList(this, R.color.primary)
        val inactiveBg = ContextCompat.getColorStateList(this, R.color.card_stroke)
        val activeText = ContextCompat.getColor(this, R.color.white)
        val inactiveText = ContextCompat.getColor(this, R.color.text_muted)

        btnFilterAll.backgroundTintList = if (currentFilter == "ALL") activeBg else inactiveBg
        btnFilterAll.setTextColor(if (currentFilter == "ALL") activeText else inactiveText)

        btnFilterQueued.backgroundTintList = if (currentFilter == "QUEUED") activeBg else inactiveBg
        btnFilterQueued.setTextColor(if (currentFilter == "QUEUED") activeText else inactiveText)

        btnFilterSynced.backgroundTintList = if (currentFilter == "SYNCED") activeBg else inactiveBg
        btnFilterSynced.setTextColor(if (currentFilter == "SYNCED") activeText else inactiveText)
    }

    private fun performRefreshAndSync() {
        lifecycleScope.launch {
            val isOnline = NetworkManager.isOnline(this@TransactionHistoryActivity)
            var syncSuccess = false

            if (isOnline) {
                withContext(Dispatchers.IO) {
                    // 1. Direct synchronous ledger sync with Central Bank
                    syncSuccess = SyncManager.syncNow(this@TransactionHistoryActivity)

                    // 2. Fetch fresh bank balance
                    try {
                        val sessionManager = SessionManager.getInstance(this@TransactionHistoryActivity)
                        val apiService = BankApiClient.getApiService(this@TransactionHistoryActivity)
                        val userResp = apiService.getUserById(sessionManager.getUser().id.toLong())
                        if (userResp.isSuccessful && userResp.body() != null) {
                            val bankUser = userResp.body()!!
                            sessionManager.updateBalances(bankUser.balance, bankUser.emergencyBalance)
                            appDatabase.walletDao.updateBalance(bankUser.emergencyBalance)
                        }

                        // 3. Fetch remote transactions to show incoming received payments in history
                        val txResp = apiService.getAllTransactions()
                        if (txResp.isSuccessful && txResp.body() != null) {
                            val remoteList = txResp.body()!!
                            val currentUser = sessionManager.getUser()
                            val curIdStr = currentUser.id.toString()
                            val curName = currentUser.name.trim()
                            val curPhone = currentUser.phone.trim()

                            remoteList.forEach { rt ->
                                val rtTxId = rt.txId ?: "TX-${rt.id}"
                                val sId = rt.senderId?.toString() ?: ""
                                val rId = rt.receiverId?.toString() ?: ""
                                val pName = rt.payee?.trim() ?: ""

                                val isParticipant = sId == curIdStr || rId == curIdStr ||
                                                    pName.equals(curName, true) || pName.equals(curPhone, true)

                                if (isParticipant) {
                                    val isOut = sId == curIdStr && !pName.equals(curName, true)
                                    val direction = if (isOut) TransactionEntity.DIRECTION_SENT else TransactionEntity.DIRECTION_RECEIVED
                                    val txChannel = rt.channel ?: if (rt.isOffline == 1) "Bluetooth" else "UPI"

                                    val existing = appDatabase.transactionDao.getTransaction(rtTxId)
                                    if (existing == null) {
                                        val newTx = TransactionEntity(
                                            txId = rtTxId,
                                            senderId = sId,
                                            senderName = if (isOut) curName else (if (pName.isNotEmpty() && !pName.equals(curName, true)) pName else "Sender"),
                                            receiverId = rId,
                                            receiverName = if (isOut) pName else curName,
                                            amount = rt.amount,
                                            timestamp = System.currentTimeMillis(),
                                            nonce = "REMOTE",
                                            signature = "VERIFIED",
                                            status = TransactionEntity.STATUS_SYNCED,
                                            direction = direction,
                                            channel = txChannel
                                        )
                                        appDatabase.transactionDao.insertTransaction(newTx)
                                    } else if (existing.status != TransactionEntity.STATUS_SYNCED) {
                                        appDatabase.transactionDao.markAsSynced(rtTxId)
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // Ignore individual network poll failure
                    }
                }
            }

            // 3. Reload transactions from Room DB
            val txs = withContext(Dispatchers.IO) {
                appDatabase.transactionDao.getAllTransactions()
            }
            allTransactions = txs
            updateSummary(txs)
            renderTransactions()

            // 4. Stop swipe spinner
            swipeRefreshLayout.isRefreshing = false

            if (isOnline) {
                val pendingRemaining = txs.count { it.status == TransactionEntity.STATUS_PENDING_SYNC }
                if (pendingRemaining == 0) {
                    Toast.makeText(this@TransactionHistoryActivity, "Ledger synchronized: All payments settled", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@TransactionHistoryActivity, "Ledger updated ($pendingRemaining queued sync)", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this@TransactionHistoryActivity, "Offline: Loaded from local offline ledger", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadTransactions() {
        performRefreshAndSync()
    }

    private fun updateSummary(txs: List<TransactionEntity>) {
        var totalAmount = 0.0
        var queuedCount = 0
        var syncedCount = 0

        for (tx in txs) {
            totalAmount += tx.amount
            if (tx.status == TransactionEntity.STATUS_PENDING_SYNC) {
                queuedCount++
            } else if (tx.status == TransactionEntity.STATUS_SYNCED) {
                syncedCount++
            }
        }

        tvTotalOfflineExchanged.text = String.format(Locale.US, "₹ %.2f", totalAmount)
        tvQueuedSyncCount.text = "$queuedCount Receipts"
        tvSyncedCount.text = "$syncedCount Receipts"
    }

    private fun renderTransactions() {
        layoutHistoryContainer.removeAllViews()

        val filtered = when (currentFilter) {
            "QUEUED" -> allTransactions.filter { it.status == TransactionEntity.STATUS_PENDING_SYNC }
            "SYNCED" -> allTransactions.filter { it.status == TransactionEntity.STATUS_SYNCED }
            else -> allTransactions
        }

        if (filtered.isEmpty()) {
            tvEmptyState.visibility = View.VISIBLE
            return
        }
        tvEmptyState.visibility = View.GONE

        val inflater = LayoutInflater.from(this)
        for (tx in filtered) {
            val itemView = inflater.inflate(R.layout.item_ledger_transaction, layoutHistoryContainer, false)
            val ivIcon = itemView.findViewById<ImageView>(R.id.ivTxIcon)
            val tvPayee = itemView.findViewById<TextView>(R.id.tvTxPayee)
            val tvId = itemView.findViewById<TextView>(R.id.tvTxId)
            val tvAmount = itemView.findViewById<TextView>(R.id.tvTxAmount)
            val tvStatus = itemView.findViewById<TextView>(R.id.tvTxStatusBadge)

            val isOut = tx.direction == TransactionEntity.DIRECTION_SENT
            val partyName = if (isOut) tx.receiverName.ifEmpty { tx.receiverId } else tx.senderName.ifEmpty { tx.senderId }
            tvPayee.text = if (isOut) "To: $partyName" else "From: $partyName"
            tvAmount.text = String.format(Locale.US, if (isOut) "- ₹%.2f" else "+ ₹%.2f", tx.amount)

            if (isOut) {
                tvAmount.setTextColor(Color.parseColor("#111827"))
            } else {
                tvAmount.setTextColor(Color.parseColor("#15803D")) // Crisp Forest Green for incoming money
                tvAmount.paint.isFakeBoldText = true
            }

            val dateStr = timeFormat.format(Date(tx.timestamp))
            val isOnlineTx = tx.isOnline
            val channelLabel = if (isOnlineTx) "UPI" else "Bluetooth"
            val dirLabel = if (isOut) "Sent" else "Received"
            tvId.text = "$channelLabel • $dirLabel • $dateStr"

            if (isOnlineTx) {
                ivIcon.setImageResource(R.drawable.ic_bank)
                ivIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.primary)
            } else {
                ivIcon.setImageResource(R.drawable.ic_bluetooth)
                ivIcon.imageTintList = ContextCompat.getColorStateList(this, if (isOut) R.color.primary else R.color.status_online_text)
            }

            when (tx.status) {
                TransactionEntity.STATUS_SYNCED -> {
                    tvStatus.text = "Completed"
                    tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_online_text))
                    tvStatus.background = ContextCompat.getDrawable(this, R.drawable.bg_badge_pill)
                }
                TransactionEntity.STATUS_PENDING_SYNC -> {
                    tvStatus.text = "Queued Sync"
                    tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_offline_text))
                    tvStatus.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_offline_bg)
                }
                else -> {
                    tvStatus.text = "Pending"
                    tvStatus.setTextColor(ContextCompat.getColor(this, R.color.emergency_dark))
                    tvStatus.backgroundTintList = ContextCompat.getColorStateList(this, R.color.emergency_bg)
                }
            }

            layoutHistoryContainer.addView(itemView)
        }
    }
}
