package com.example.empay

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
import com.example.empay.db.AppDatabase
import com.example.empay.db.TransactionEntity
import com.example.empay.sync.SyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TransactionHistoryActivity : AppCompatActivity() {

    private lateinit var appDatabase: AppDatabase
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

    private fun loadTransactions() {
        lifecycleScope.launch(Dispatchers.IO) {
            val txs = appDatabase.transactionDao.getAllTransactions()
            withContext(Dispatchers.Main) {
                allTransactions = txs
                updateSummary(txs)
                renderTransactions()
            }
        }
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
            tvPayee.text = if (isOut) tx.receiverName.ifEmpty { tx.receiverId } else tx.senderName.ifEmpty { tx.senderId }
            tvAmount.text = String.format(Locale.US, if (isOut) "- ₹%.2f" else "+ ₹%.2f", tx.amount)

            val dateStr = timeFormat.format(Date(tx.timestamp))
            tvId.text = "Bluetooth • $dateStr"

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
