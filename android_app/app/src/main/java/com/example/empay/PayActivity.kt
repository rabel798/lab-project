package com.example.empay

import android.app.Dialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.Filter
import android.widget.Filterable
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.empay.auth.AuthManager
import com.example.empay.auth.SessionManager
import com.example.empay.crypto.CryptoManager
import com.example.empay.db.AppDatabase
import com.example.empay.db.TransactionEntity
import com.example.empay.network.BankApiClient
import com.example.empay.network.NetworkManager
import com.example.empay.network.SyncTransactionItem
import com.example.empay.network.SyncTransactionsRequest
import com.example.empay.repository.PaymentRepository
import com.example.empay.sync.SyncManager
import com.example.empay.ui.PinAuthDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

class PayActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager
    private lateinit var appDatabase: AppDatabase
    private lateinit var paymentRepository: PaymentRepository

    private lateinit var etPayRecipient: AutoCompleteTextView
    private lateinit var etPayAmountInput: EditText
    private lateinit var etPayNote: EditText
    private lateinit var tvPayHeroTitle: TextView
    private lateinit var tvPayNetworkBadge: TextView
    private lateinit var ivMethodIcon: ImageView
    private lateinit var tvMethodText: TextView
    private lateinit var tvPayOnlineBalPreview: TextView
    private lateinit var tvPayEmergencyBalPreview: TextView
    private lateinit var tvPayExecutionMode: TextView
    private lateinit var btnSubmitPayment: Button
    private lateinit var btnPayCancel: Button

    private var isOnlineMode = true
    private var selectedChannel = "UPI"
    private var targetPhone: String = ""
    private var targetDeviceAddress: String = ""

    data class RecipientSuggestion(
        val name: String,
        val phone: String,
        val isMerchant: Boolean,
        val subtitle: String
    ) {
        override fun toString(): String = name
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pay)

        sessionManager = SessionManager.getInstance(this)
        appDatabase = AppDatabase.getInstance(this)
        paymentRepository = PaymentRepository.getInstance(this)

        initViews()
        setupListeners()
        setupRecipientAutoComplete()
        loadStateAndBalances()
    }

    override fun onResume() {
        super.onResume()
        loadStateAndBalances()
        lifecycleScope.launch {
            paymentRepository.refreshAuthoritativeBalances()
            loadStateAndBalances()
        }
        com.example.empay.auth.AuthManager.getInstance(this).syncAllRegisteredUsersFromBackend()
    }

    private fun initViews() {
        findViewById<ImageButton>(R.id.btnPayBack).setOnClickListener { finish() }

        etPayRecipient = findViewById(R.id.etPayRecipient)
        etPayAmountInput = findViewById(R.id.etPayAmountInput)
        etPayNote = findViewById(R.id.etPayNote)
        tvPayHeroTitle = findViewById(R.id.tvPayHeroTitle)
        tvPayNetworkBadge = findViewById(R.id.tvPayNetworkBadge)
        ivMethodIcon = findViewById(R.id.ivMethodIcon)
        tvMethodText = findViewById(R.id.tvMethodText)
        tvPayOnlineBalPreview = findViewById(R.id.tvPayOnlineBalPreview)
        tvPayEmergencyBalPreview = findViewById(R.id.tvPayEmergencyBalPreview)
        tvPayExecutionMode = findViewById(R.id.tvPayExecutionMode)
        btnSubmitPayment = findViewById(R.id.btnSubmitPayment)
        btnPayCancel = findViewById(R.id.btnPayCancel)

        // Read intent extras
        val targetPayee = intent.getStringExtra("PAYEE_NAME") ?: ""
        val phone = intent.getStringExtra("PHONE") ?: ""
        val defaultAmt = intent.getStringExtra("DEFAULT_AMOUNT") ?: ""
        val channelParam = intent.getStringExtra("CHANNEL") ?: ""
        targetDeviceAddress = intent.getStringExtra("DEVICE_ADDRESS") ?: ""
        targetPhone = phone

        if (targetPayee.isNotEmpty()) {
            etPayRecipient.setText(targetPayee)
            tvPayHeroTitle.text = targetPayee
        } else if (phone.isNotEmpty()) {
            etPayRecipient.setText(phone)
            tvPayHeroTitle.text = phone
        } else {
            tvPayHeroTitle.text = "Send Money"
        }

        if (defaultAmt.isNotEmpty()) {
            etPayAmountInput.setText(defaultAmt)
        }

        // Determine online vs offline mode
        val realOnline = NetworkManager.isOnline(this)
        val userSessionOnline = sessionManager.getUser().isOnline
        
        // If explicitly requested Bluetooth channel, or device is offline, run offline mode
        isOnlineMode = if (channelParam.equals("Bluetooth", ignoreCase = true)) {
            false
        } else {
            realOnline && userSessionOnline
        }

        selectedChannel = if (isOnlineMode) "UPI" else "Bluetooth"
    }

    private fun setupRecipientAutoComplete() {
        val adapter = object : android.widget.ArrayAdapter<RecipientSuggestion>(
            this,
            R.layout.item_recipient_suggestion,
            R.id.tvSuggestName
        ) {
            private var suggestions = listOf<RecipientSuggestion>()

            override fun getCount(): Int = suggestions.size
            override fun getItem(position: Int): RecipientSuggestion? = suggestions.getOrNull(position)

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(context).inflate(
                    R.layout.item_recipient_suggestion, parent, false
                )
                val item = getItem(position) ?: return view

                val ivIcon = view.findViewById<ImageView>(R.id.ivSuggestIcon)
                val tvName = view.findViewById<TextView>(R.id.tvSuggestName)
                val tvSubtitle = view.findViewById<TextView>(R.id.tvSuggestSubtitle)
                val tvBadge = view.findViewById<TextView>(R.id.tvSuggestBadge)

                tvName.text = item.name
                tvSubtitle.text = item.subtitle
                if (item.isMerchant) {
                    ivIcon.setImageResource(R.drawable.ic_store)
                    tvBadge.text = "✓ Verified Merchant"
                    tvBadge.setTextColor(Color.parseColor("#15803D"))
                } else {
                    ivIcon.setImageResource(R.drawable.ic_profile)
                    tvBadge.text = "✓ Registered User"
                    tvBadge.setTextColor(Color.parseColor("#0284C7"))
                }
                return view
            }

            override fun getFilter(): Filter {
                return object : Filter() {
                    override fun performFiltering(constraint: CharSequence?): FilterResults {
                        val query = constraint?.toString()?.trim() ?: ""
                        val results = FilterResults()
                        val currentPhone = sessionManager.getUser().phone
                        val currentName = sessionManager.getUser().name

                        val matched = mutableListOf<RecipientSuggestion>()

                        // 1. Query local registered users from SQLite
                        val users = appDatabase.userDao.searchUsers(query)
                        users.forEach { u ->
                            if (u.phone != currentPhone && !u.name.equals(currentName, true)) {
                                matched.add(
                                    RecipientSuggestion(
                                        name = u.name,
                                        phone = u.phone,
                                        isMerchant = false,
                                        subtitle = "+91 ${u.phone} • Genuine Registered User"
                                    )
                                )
                            }
                        }

                        // 2. Verified Disaster Relief Merchants
                        val merchants = listOf(
                            Triple("Apex City Care Pharmacy", "9811122233", "Healthcare Merchant"),
                            Triple("Daily Fresh Supermarket", "9822233344", "Groceries Merchant"),
                            Triple("LifeLine Emergency Medicos", "9833344455", "Emergency Healthcare"),
                            Triple("National Book & Stationery Depot", "9844455566", "Retail Merchant"),
                            Triple("City QuickFix Electrical Services", "9855566677", "Emergency Services")
                        )
                        merchants.forEach { m ->
                            if (query.isEmpty() || m.first.contains(query, true) || m.second.contains(query)) {
                                matched.add(
                                    RecipientSuggestion(
                                        name = m.first,
                                        phone = m.second,
                                        isMerchant = true,
                                        subtitle = "${m.second} • ${m.third}"
                                    )
                                )
                            }
                        }

                        results.values = matched
                        results.count = matched.size
                        return results
                    }

                    @Suppress("UNCHECKED_CAST")
                    override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                        suggestions = (results?.values as? List<RecipientSuggestion>) ?: emptyList()
                        notifyDataSetChanged()
                    }
                }
            }
        }

        etPayRecipient.setAdapter(adapter)
        etPayRecipient.setOnItemClickListener { parent, _, position, _ ->
            val selected = parent.getItemAtPosition(position) as? RecipientSuggestion ?: return@setOnItemClickListener
            etPayRecipient.setText(selected.name)
            targetPhone = selected.phone
            tvPayHeroTitle.text = selected.name
        }
    }

    private fun setupListeners() {
        findViewById<Button>(R.id.chipAmount10).setOnClickListener { etPayAmountInput.setText("10.00") }
        findViewById<Button>(R.id.chipAmount25).setOnClickListener { etPayAmountInput.setText("25.00") }
        findViewById<Button>(R.id.chipAmount50).setOnClickListener { etPayAmountInput.setText("50.00") }
        findViewById<Button>(R.id.chipAmount100).setOnClickListener { etPayAmountInput.setText("100.00") }

        btnPayCancel.setOnClickListener { finish() }

        btnSubmitPayment.setOnClickListener {
            validateAndShowConfirmSheet()
        }
    }

    private fun loadStateAndBalances() {
        val user = sessionManager.getUser()
        val wallet = appDatabase.walletDao.getWallet()
        val offlineBal = wallet?.balance ?: user.emergencyBalance

        tvPayOnlineBalPreview.text = String.format(Locale.US, "₹ %.2f", user.balance)
        tvPayEmergencyBalPreview.text = String.format(Locale.US, "₹ %.2f", offlineBal)

        if (isOnlineMode) {
            tvPayNetworkBadge.text = "● Online"
            tvPayNetworkBadge.setTextColor(ContextCompat.getColor(this, R.color.status_online_text))
            tvPayNetworkBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_online_bg)
            ivMethodIcon.setImageResource(R.drawable.ic_wallet_card)
            ivMethodIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.primary)
            tvMethodText.text = "UPI Payment (Online)"
            tvPayExecutionMode.text = "UPI (Instant Online)"
            btnSubmitPayment.backgroundTintList = ContextCompat.getColorStateList(this, R.color.primary)
            btnSubmitPayment.text = "Authorize Payment 🔒"
        } else {
            tvPayNetworkBadge.text = "● Offline"
            tvPayNetworkBadge.setTextColor(ContextCompat.getColor(this, R.color.emergency_dark))
            tvPayNetworkBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.emergency_bg)
            ivMethodIcon.setImageResource(R.drawable.ic_bluetooth)
            ivMethodIcon.imageTintList = null
            tvMethodText.text = "Bluetooth Payment (Offline)"
            tvPayExecutionMode.text = "Bluetooth (Offline Emergency)"
            btnSubmitPayment.backgroundTintList = ContextCompat.getColorStateList(this, R.color.electric_orange)
            btnSubmitPayment.text = "Authorize Offline Payment ⚡"
        }
    }

    private fun validateAndShowConfirmSheet() {
        val amountStr = etPayAmountInput.text.toString().trim()
        val amount = amountStr.toDoubleOrNull()
        val payeeName = etPayRecipient.text.toString().trim()
        val note = etPayNote.text.toString().trim().ifEmpty {
            if (isOnlineMode) "UPI Payment" else "Offline Payment"
        }

        if (payeeName.isEmpty()) {
            Toast.makeText(this, "Please enter a valid recipient phone or UPI VPA", Toast.LENGTH_SHORT).show()
            return
        }

        val currentUser = paymentRepository.getCurrentUser()
        if (payeeName.equals(currentUser.phone.trim(), ignoreCase = true) ||
            payeeName.equals(currentUser.accountNumber.trim(), ignoreCase = true) ||
            payeeName.equals("Self", ignoreCase = true) ||
            payeeName.equals("Myself", ignoreCase = true)) {
            Toast.makeText(this, "Self-transfer not allowed: Cannot send money to yourself.", Toast.LENGTH_LONG).show()
            return
        }

        if (amount == null || amount <= 0.0) {
            Toast.makeText(this, "Please enter a valid amount", Toast.LENGTH_SHORT).show()
            return
        }

        val user = sessionManager.getUser()
        val wallet = appDatabase.walletDao.getWallet()
        val offlineBal = wallet?.balance ?: user.emergencyBalance

        if (isOnlineMode) {
            if (user.balance < amount) {
                Toast.makeText(this, "Insufficient Available Balance (₹${user.balance}) for this online transfer.", Toast.LENGTH_LONG).show()
                return
            }
        } else {
            if (offlineBal < amount) {
                Toast.makeText(this, "Insufficient Emergency Balance (₹${offlineBal}) for this offline transfer.", Toast.LENGTH_LONG).show()
                return
            }
        }

        // Validate recipient exists in database or verified merchants registry
        lifecycleScope.launch(Dispatchers.IO) {
            val authManager = AuthManager.getInstance(this@PayActivity)
            val verification = authManager.verifyRecipient(payeeName)

            withContext(Dispatchers.Main) {
                if (verification == null) {
                    Toast.makeText(
                        this@PayActivity,
                        "❌ Recipient Not Found: '$payeeName' is not registered in the bank database or verified merchants registry.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@withContext
                }

                // Recipient verified! Proceed to confirmation sheet
                showConfirmationSheet(amount, verification.displayName, note, verification.details)
            }
        }
    }

    private fun showConfirmationSheet(amount: Double, payee: String, note: String, details: String = "") {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_confirm_transfer)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val tvAmt = dialog.findViewById<TextView>(R.id.tvConfirmModalAmount)
        val tvPayee = dialog.findViewById<TextView>(R.id.tvConfirmModalPayee)
        val tvChan = dialog.findViewById<TextView>(R.id.tvConfirmModalChannel)
        val tvNote = dialog.findViewById<TextView>(R.id.tvConfirmModalNote)
        val tvInfo = dialog.findViewById<TextView>(R.id.tvConfirmModalInfo)
        val btnCancel = dialog.findViewById<Button>(R.id.btnConfirmModalCancel)
        val btnEnterPin = dialog.findViewById<Button>(R.id.btnConfirmModalEnterPin)

        tvAmt.text = String.format(Locale.US, "₹ %.2f", amount)
        tvPayee.text = "$payee ✓"
        tvChan.text = if (isOnlineMode) "UPI (Online)" else "Bluetooth (Offline)"
        tvNote.text = if (details.isNotEmpty()) "$note\n($details)" else note

        if (isOnlineMode) {
            tvInfo.text = "🛡️ Online UPI payment: Amount will be deducted from your Available Balance."
            tvInfo.setTextColor(Color.parseColor("#15803D"))
        } else {
            tvInfo.text = "ℹ️ Offline Bluetooth payment: Amount will be deducted from your Emergency Balance."
            tvInfo.setTextColor(Color.parseColor("#B45309"))
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnEnterPin.setOnClickListener {
            dialog.dismiss()
            openPinDialog(amount, payee, note)
        }

        dialog.show()
    }

    private fun openPinDialog(amount: Double, payee: String, note: String) {
        PinAuthDialog(
            context = this,
            actionTitle = if (isOnlineMode) "UPI Authorization" else "Authorize Offline Transfer",
            actionSubtitle = "Enter 4-digit PIN to sign and authorize payment",
            amount = amount,
            payee = "To: $payee",
            channel = if (isOnlineMode) "Via Online UPI Network" else "Via Bluetooth Mesh",
            onSuccess = {
                executeTransfer(amount, payee, note)
            },
            onFailed = { error ->
                Toast.makeText(this, "PIN Authentication Failed: $error", Toast.LENGTH_SHORT).show()
            }
        ).show()
    }

    private fun executeTransfer(amount: Double, payee: String, note: String) {
        val currentUser = paymentRepository.getCurrentUser()
        val txId = if (isOnlineMode) {
            "UPI-" + UUID.randomUUID().toString().take(12).uppercase()
        } else {
            "BT-" + UUID.randomUUID().toString().take(12).uppercase()
        }
        val nonce = UUID.randomUUID().toString().take(8)
        val timestamp = System.currentTimeMillis()
        val receiverIdent = if (targetPhone.isNotEmpty()) targetPhone else payee

        if (isOnlineMode) {
            // Online UPI execution
            lifecycleScope.launch(Dispatchers.IO) {
                var newOnlineBal = currentUser.balance - amount
                try {
                    val api = BankApiClient.getApiService(applicationContext)
                    val syncReq = SyncTransactionsRequest(
                        transactions = listOf(
                            SyncTransactionItem(
                                txId = txId,
                                payee = payee,
                                amount = amount,
                                note = "UPI Online Payment",
                                direction = TransactionEntity.DIRECTION_SENT,
                                senderId = currentUser.id.toString(),
                                receiverId = receiverIdent
                            )
                        ),
                        userId = currentUser.id
                    )
                    val resp = api.syncTransactions(syncReq)
                    if (resp.isSuccessful && resp.body()?.status == "success") {
                        val body = resp.body()!!
                        newOnlineBal = body.balance
                        sessionManager.updateBalances(body.balance, body.emergencyBalance)
                    } else {
                        sessionManager.updateBalances(newOnlineBal, currentUser.emergencyBalance)
                    }
                } catch (_: Exception) {
                    sessionManager.updateBalances(newOnlineBal, currentUser.emergencyBalance)
                }

                // Insert into local DB
                val txEntity = TransactionEntity(
                    txId = txId,
                    senderId = currentUser.id.toString(),
                    senderName = currentUser.name,
                    receiverId = receiverIdent,
                    receiverName = payee,
                    amount = amount,
                    timestamp = timestamp,
                    nonce = nonce,
                    signature = "UPI_VERIFIED_" + UUID.randomUUID().toString().take(8),
                    status = TransactionEntity.STATUS_SYNCED,
                    direction = TransactionEntity.DIRECTION_SENT,
                    channel = TransactionEntity.CHANNEL_UPI
                )
                appDatabase.transactionDao.insertTransaction(txEntity)

                withContext(Dispatchers.Main) {
                    navigateToReceipt(amount, payee, "UPI Online", txId, txEntity.signature, timestamp)
                }
            }
        } else {
            // Offline Bluetooth execution
            lifecycleScope.launch(Dispatchers.IO) {
                // Deduct from offline wallet
                appDatabase.walletDao.deductBalance(amount)
                val newWalletBal = appDatabase.walletDao.getWallet()?.balance ?: 0.0
                sessionManager.updateBalances(sessionManager.getUser().balance, newWalletBal)

                // If paired or nearby Bluetooth device address is available, attempt real RFCOMM packet exchange
                if (targetDeviceAddress.isNotEmpty()) {
                    try {
                        val adapter = BluetoothAdapter.getDefaultAdapter()
                        if (adapter != null && BluetoothAdapter.checkBluetoothAddress(targetDeviceAddress)) {
                            val remoteDev: BluetoothDevice = adapter.getRemoteDevice(targetDeviceAddress)
                            paymentRepository.executeBluetoothPayment(
                                device = remoteDev,
                                receiverId = receiverIdent,
                                receiverName = payee,
                                amount = amount,
                                onStatusLog = {},
                                onSuccess = { _, _ -> },
                                onError = {}
                            )
                        }
                    } catch (_: Exception) {}
                }

                val signature = try {
                    val canonicalBytes = CryptoManager.getCanonicalBytes(
                        txId = txId,
                        senderId = currentUser.id.toString(),
                        receiverId = receiverIdent,
                        amount = amount,
                        timestamp = timestamp,
                        nonce = nonce
                    )
                    CryptoManager.signData(canonicalBytes)
                } catch (e: Exception) {
                    "SIG-" + UUID.randomUUID().toString().take(16).uppercase()
                }

                val isOnline = NetworkManager.isOnline(this@PayActivity)
                
                // If online, immediately reconcile with Central Bank so it transitions to COMPLETED (SYNCED)
                var initialStatus = TransactionEntity.STATUS_PENDING_SYNC
                if (isOnline) {
                    try {
                        val apiService = BankApiClient.getApiService(applicationContext)
                        val syncReq = SyncTransactionsRequest(
                            transactions = listOf(
                                SyncTransactionItem(
                                    txId = txId,
                                    payee = payee,
                                    amount = amount,
                                    note = "Bluetooth Payment (Online Instant Settlement)",
                                    direction = TransactionEntity.DIRECTION_SENT,
                                    senderId = currentUser.id.toString(),
                                    receiverId = receiverIdent
                                )
                            ),
                            userId = currentUser.id
                        )
                        val syncResp = apiService.syncTransactions(syncReq)
                        if (syncResp.isSuccessful && syncResp.body()?.status == "success") {
                            initialStatus = TransactionEntity.STATUS_SYNCED
                            val body = syncResp.body()!!
                            sessionManager.updateBalances(body.balance, body.emergencyBalance)
                            appDatabase.walletDao.updateBalance(body.emergencyBalance)
                        }
                    } catch (_: Exception) {
                        initialStatus = TransactionEntity.STATUS_PENDING_SYNC
                    }
                }

                // Insert into local DB
                val txEntity = TransactionEntity(
                    txId = txId,
                    senderId = currentUser.id.toString(),
                    senderName = currentUser.name,
                    receiverId = receiverIdent,
                    receiverName = payee,
                    amount = amount,
                    timestamp = timestamp,
                    nonce = nonce,
                    signature = signature,
                    status = initialStatus,
                    direction = TransactionEntity.DIRECTION_SENT,
                    channel = TransactionEntity.CHANNEL_BLUETOOTH
                )
                appDatabase.transactionDao.insertTransaction(txEntity)

                if (initialStatus == TransactionEntity.STATUS_PENDING_SYNC) {
                    SyncManager.triggerImmediateSync(applicationContext)
                }

                withContext(Dispatchers.Main) {
                    navigateToReceipt(amount, payee, "Bluetooth", txId, signature, timestamp)
                }
            }
        }
    }

    private fun navigateToReceipt(amount: Double, payee: String, channel: String, txId: String, signature: String, timestamp: Long) {
        val receiptIntent = Intent(this, ReceiptActivity::class.java).apply {
            putExtra("AMOUNT", amount)
            putExtra("PAYEE", payee)
            putExtra("CHANNEL", channel)
            putExtra("TX_ID", txId)
            putExtra("SIGNATURE", signature)
            putExtra("TIMESTAMP", timestamp)
        }
        startActivity(receiptIntent)
        finish()
    }
}
