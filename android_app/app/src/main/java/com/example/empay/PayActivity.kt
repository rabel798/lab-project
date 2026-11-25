package com.example.empay

import android.app.Dialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import androidx.appcompat.app.AlertDialog
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

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

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
        registerNetworkMonitoring()
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

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (networkCallback != null && connectivityManager != null) {
                connectivityManager?.unregisterNetworkCallback(networkCallback!!)
            }
        } catch (_: Exception) {}
    }

    private fun registerNetworkMonitoring() {
        try {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    runOnUiThread {
                        loadStateAndBalances()
                    }
                }

                override fun onLost(network: Network) {
                    runOnUiThread {
                        loadStateAndBalances()
                    }
                }
            }
            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            e.printStackTrace()
        }
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

        findViewById<View>(R.id.layoutMethodCard).setOnClickListener {
            val realOnline = NetworkManager.isOnline(this)
            if (!isOnlineMode) {
                if (!realOnline) {
                    Toast.makeText(this, "Cannot switch to Online UPI: Device is offline.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                isOnlineMode = true
                selectedChannel = "UPI"
                Toast.makeText(this, "Switched to Online UPI Payment", Toast.LENGTH_SHORT).show()
            } else {
                isOnlineMode = false
                selectedChannel = "Bluetooth"
                Toast.makeText(this, "Switched to Offline Bluetooth Payment", Toast.LENGTH_SHORT).show()
            }
            loadStateAndBalances()
        }

        btnPayCancel.setOnClickListener { finish() }

        btnSubmitPayment.setOnClickListener {
            validateAndShowConfirmSheet()
        }
    }

    private fun switchToOfflineBluetoothMode() {
        isOnlineMode = false
        selectedChannel = "Bluetooth"
        loadStateAndBalances()
        Toast.makeText(this, "Switched to Offline Bluetooth Mode. Emergency reserve active.", Toast.LENGTH_LONG).show()
    }

    private fun loadStateAndBalances() {
        val user = sessionManager.getUser()
        val wallet = appDatabase.walletDao.getWallet()
        val offlineBal = wallet?.balance ?: user.emergencyBalance
        val realOnline = NetworkManager.isOnline(this)

        tvPayOnlineBalPreview.text = String.format(Locale.US, "₹ %.2f", user.balance)
        tvPayEmergencyBalPreview.text = String.format(Locale.US, "₹ %.2f", offlineBal)

        if (isOnlineMode) {
            if (realOnline) {
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
                tvPayNetworkBadge.text = "● Offline (No Internet)"
                tvPayNetworkBadge.setTextColor(ContextCompat.getColor(this, R.color.emergency_dark))
                tvPayNetworkBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.emergency_bg)
                ivMethodIcon.setImageResource(R.drawable.ic_wallet_card)
                ivMethodIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.emergency_dark)
                tvMethodText.text = "UPI Payment (Offline - Internet Required)"
                tvPayExecutionMode.text = "UPI (Offline - No Connection)"
                btnSubmitPayment.backgroundTintList = ContextCompat.getColorStateList(this, R.color.text_secondary)
                btnSubmitPayment.text = "Authorize Payment 🔒 (Offline)"
            }
        } else {
            tvPayNetworkBadge.text = "● Offline Mode"
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

        // If in online payment mode but device is offline, prevent transaction
        if (isOnlineMode && !NetworkManager.isOnline(this)) {
            AlertDialog.Builder(this)
                .setTitle("⚠️ Transaction Not Possible")
                .setMessage("Transaction is not possible since your device is currently offline.\n\nOnline UPI transfers require an active internet connection to communicate with the Central Bank.\n\nWould you like to switch to Offline Bluetooth Payment instead?")
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setPositiveButton("Switch to Bluetooth") { _, _ ->
                    switchToOfflineBluetoothMode()
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        // If in offline Bluetooth mode, ensure Bluetooth is currently enabled
        if (!isOnlineMode || selectedChannel.equals("Bluetooth", true)) {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null || !adapter.isEnabled) {
                AlertDialog.Builder(this)
                    .setTitle("⚠️ Bluetooth Not Detected")
                    .setMessage("Your Bluetooth is turned off. Please turn on Bluetooth to proceed with offline payment.")
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setPositiveButton("OK", null)
                    .show()
                return
            }
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
                val finalVerification = verification ?: if (!isOnlineMode || selectedChannel.equals("Bluetooth", true) || targetDeviceAddress.isNotEmpty()) {
                    com.example.empay.auth.RecipientVerificationResult(
                        isRegistered = true,
                        displayName = payeeName,
                        phone = targetPhone,
                        isMerchant = false,
                        details = "Offline Bluetooth Mesh Peer"
                    )
                } else null

                if (finalVerification == null) {
                    Toast.makeText(
                        this@PayActivity,
                        "❌ Recipient Not Found: '$payeeName' is not registered in the bank database or verified merchants registry.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@withContext
                }

                // Recipient verified! Proceed to confirmation sheet
                showConfirmationSheet(amount, finalVerification.displayName, note, finalVerification.details)
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
                if (isOnlineMode && !NetworkManager.isOnline(this)) {
                    AlertDialog.Builder(this)
                        .setTitle("⚠️ Transaction Not Possible")
                        .setMessage("Transaction not possible since your device is currently offline.\n\nOnline UPI payments require an active internet connection.\n\nYour account balance has NOT been debited.")
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setPositiveButton("Switch to Bluetooth") { _, _ ->
                            switchToOfflineBluetoothMode()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    loadStateAndBalances()
                    return@PinAuthDialog
                }
                if (!isOnlineMode || selectedChannel.equals("Bluetooth", true)) {
                    val adapter = BluetoothAdapter.getDefaultAdapter()
                    if (adapter == null || !adapter.isEnabled) {
                        AlertDialog.Builder(this)
                            .setTitle("⚠️ Bluetooth Not Detected")
                            .setMessage("Bluetooth was turned off. Offline payment cannot proceed.\n\nYour account balance has NOT been debited.")
                            .setIcon(android.R.drawable.ic_dialog_alert)
                            .setPositiveButton("OK", null)
                            .show()
                        loadStateAndBalances()
                        return@PinAuthDialog
                    }
                }
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
            if (!NetworkManager.isOnline(applicationContext)) {
                AlertDialog.Builder(this)
                    .setTitle("⚠️ Transaction Not Possible")
                    .setMessage("Transaction not possible since your device is offline.\n\nPlease reconnect to the internet to complete this online transfer.\n\nYour account balance has NOT been debited.")
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setPositiveButton("OK", null)
                    .show()
                loadStateAndBalances()
                return
            }

            // Online UPI execution
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val api = BankApiClient.getApiService(applicationContext)
                    val syncReq = SyncTransactionsRequest(
                        transactions = listOf(
                            SyncTransactionItem(
                                txId = txId,
                                payee = payee,
                                amount = amount,
                                note = note.ifEmpty { "UPI Online Payment" },
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
                        sessionManager.updateBalances(body.balance, body.emergencyBalance)

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
                    } else {
                        val errMsg = resp.errorBody()?.string() ?: resp.message()
                        withContext(Dispatchers.Main) {
                            AlertDialog.Builder(this@PayActivity)
                                .setTitle("Transaction Declined")
                                .setMessage("Central Bank rejected the transaction: ${if (errMsg.isNullOrBlank()) "Unable to process payment." else errMsg}\n\nYour account balance has NOT been debited.")
                                .setIcon(android.R.drawable.ic_dialog_alert)
                                .setPositiveButton("OK", null)
                                .show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        AlertDialog.Builder(this@PayActivity)
                            .setTitle("⚠️ Transaction Not Possible")
                            .setMessage("Transaction not possible since you are offline or the connection to the Central Bank server was lost.\n\nYour account balance has NOT been debited.")
                            .setIcon(android.R.drawable.ic_dialog_alert)
                            .setPositiveButton("OK", null)
                            .show()
                        loadStateAndBalances()
                    }
                }
            }
        } else {
            // Offline Bluetooth execution
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null || !adapter.isEnabled) {
                AlertDialog.Builder(this)
                    .setTitle("⚠️ Bluetooth Not Detected")
                    .setMessage("Your Bluetooth is turned off. Please turn on Bluetooth to complete this offline payment.\n\nYour balance has NOT been debited.")
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setPositiveButton("OK", null)
                    .show()
                loadStateAndBalances()
                return
            }

            val isTargetingDevice = targetDeviceAddress.isNotEmpty()
            val progressDialog = if (isTargetingDevice) {
                @Suppress("DEPRECATION")
                android.app.ProgressDialog(this).apply {
                    setTitle("Bluetooth Payment")
                    setMessage("Connecting to $payee over Bluetooth...")
                    setCancelable(false)
                    show()
                }
            } else null

            lifecycleScope.launch(Dispatchers.IO) {
                var connectionOk = true
                var failMessage = ""

                if (isTargetingDevice) {
                    val remoteDev: BluetoothDevice? = try {
                        if (BluetoothAdapter.checkBluetoothAddress(targetDeviceAddress)) {
                            adapter.getRemoteDevice(targetDeviceAddress)
                        } else null
                    } catch (_: Exception) { null }

                    if (remoteDev == null) {
                        connectionOk = false
                        failMessage = "Target Bluetooth device is unreachable."
                    } else {
                        val latch = java.util.concurrent.CountDownLatch(1)
                        paymentRepository.executeBluetoothPayment(
                            device = remoteDev,
                            receiverId = receiverIdent,
                            receiverName = payee,
                            amount = amount,
                            onStatusLog = {},
                            onSuccess = { _, _ ->
                                connectionOk = true
                                latch.countDown()
                            },
                            onError = { err ->
                                connectionOk = false
                                failMessage = err
                                latch.countDown()
                            }
                        )
                        val connected = latch.await(6, java.util.concurrent.TimeUnit.SECONDS)
                        if (!connected || !connectionOk) {
                            connectionOk = false
                            if (failMessage.isEmpty()) {
                                failMessage = "Connection timed out. Recipient's Bluetooth was turned off or out of range."
                            }
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    try { progressDialog?.dismiss() } catch (_: Exception) {}
                }

                if (!connectionOk) {
                    withContext(Dispatchers.Main) {
                        AlertDialog.Builder(this@PayActivity)
                            .setTitle("⚠️ Bluetooth Not Detected")
                            .setMessage("Payment locked: Could not connect to '$payee' via Bluetooth.\n\nThe recipient's Bluetooth is turned off or out of range.\n\nYour balance has NOT been debited.")
                            .setIcon(android.R.drawable.ic_dialog_alert)
                            .setPositiveButton("OK", null)
                            .show()
                        loadStateAndBalances()
                    }
                    return@launch
                }

                // Deduct from offline wallet ONLY after Bluetooth connection succeeds
                appDatabase.walletDao.deductBalance(amount)
                val newWalletBal = appDatabase.walletDao.getWallet()?.balance ?: 0.0
                sessionManager.updateBalances(sessionManager.getUser().balance, newWalletBal)

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
                                    note = "Bluetooth Offline Payment",
                                    direction = TransactionEntity.DIRECTION_SENT,
                                    senderId = currentUser.id.toString(),
                                    receiverId = receiverIdent,
                                    channel = "Bluetooth"
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
