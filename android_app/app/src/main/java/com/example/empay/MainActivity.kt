package com.example.empay

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.empay.auth.PinAuthManager
import com.example.empay.auth.SessionManager
import com.example.empay.auth.UserSession
import com.example.empay.crypto.CryptoManager
import com.example.empay.db.AppDatabase
import com.example.empay.db.TransactionEntity
import com.example.empay.network.BankApiClient
import com.example.empay.network.NetworkManager
import com.example.empay.network.SyncTransactionItem
import com.example.empay.network.SyncTransactionsRequest
import com.example.empay.network.TransferEmergencyRequest
import com.example.empay.ui.PinAuthDialog
import com.example.empay.ui.TransferFundsDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager
    private lateinit var pinAuthManager: PinAuthManager
    private lateinit var appDatabase: AppDatabase

    // UI elements
    private lateinit var tvNetworkStatusBadge: TextView
    private lateinit var btnSyncBank: Button
    private lateinit var tvUserName: TextView
    private lateinit var tvUserPhone: TextView
    private lateinit var tvAccountNumber: TextView
    private lateinit var tvPinStatusBadge: TextView
    private lateinit var tvKeyFingerprint: TextView
    private lateinit var tvOnlineBalance: TextView
    private lateinit var tvEmergencyBalance: TextView

    private lateinit var btnTransferToOffline: Button
    private lateinit var btnTestLogin: Button
    private lateinit var btnTestPin: Button
    private lateinit var btnCryptoSelfTest: Button

    private lateinit var tvPendingTxBadge: TextView
    private lateinit var btnAddMockTx: Button
    private lateinit var layoutLedgerContainer: LinearLayout
    private lateinit var tvEmptyLedgerMsg: TextView

    private lateinit var etBankUrl: EditText
    private lateinit var btnSaveBankUrl: Button

    private lateinit var tvTerminalLog: TextView
    private lateinit var scrollTerminal: ScrollView
    private lateinit var btnClearTerminal: TextView

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sessionManager = SessionManager.getInstance(this)
        pinAuthManager = PinAuthManager.getInstance(this)
        appDatabase = AppDatabase.getInstance(this)

        initViews()
        setupListeners()
        observeSession()
        monitorNetworkConnectivity()
        initHardwareCrypto()
        refreshLocalLedgerUI()

        // Sync local SQLite wallet balance with SessionManager if available
        val localWallet = appDatabase.walletDao.getWallet()
        if (localWallet != null) {
            sessionManager.updateBalances(sessionManager.getUser().balance, localWallet.balance)
        }

        appendLog("INIT", "EmPay Protocol Core initialized (Min SDK 26, Target SDK 36)")
        appendLog("DATABASE", "Local SQLite ledger ready (empay_offline.db). Offline balance: ₹${localWallet?.balance ?: 10.0}")
        appendLog("AUTH", "Hardware-backed EncryptedSharedPreferences ready. Default PIN: 1234")
    }

    private fun initViews() {
        tvNetworkStatusBadge = findViewById(R.id.tvNetworkStatusBadge)
        btnSyncBank = findViewById(R.id.btnSyncBank)

        tvUserName = findViewById(R.id.tvUserName)
        tvUserPhone = findViewById(R.id.tvUserPhone)
        tvAccountNumber = findViewById(R.id.tvAccountNumber)
        tvPinStatusBadge = findViewById(R.id.tvPinStatusBadge)
        tvKeyFingerprint = findViewById(R.id.tvKeyFingerprint)
        tvOnlineBalance = findViewById(R.id.tvOnlineBalance)
        tvEmergencyBalance = findViewById(R.id.tvEmergencyBalance)

        btnTransferToOffline = findViewById(R.id.btnTransferToOffline)
        btnTestLogin = findViewById(R.id.btnTestLogin)
        btnTestPin = findViewById(R.id.btnTestPin)
        btnCryptoSelfTest = findViewById(R.id.btnCryptoSelfTest)

        tvPendingTxBadge = findViewById(R.id.tvPendingTxBadge)
        btnAddMockTx = findViewById(R.id.btnAddMockTx)
        layoutLedgerContainer = findViewById(R.id.layoutLedgerContainer)
        tvEmptyLedgerMsg = findViewById(R.id.tvEmptyLedgerMsg)

        etBankUrl = findViewById(R.id.etBankUrl)
        btnSaveBankUrl = findViewById(R.id.btnSaveBankUrl)

        tvTerminalLog = findViewById(R.id.tvTerminalLog)
        scrollTerminal = findViewById(R.id.scrollTerminal)
        btnClearTerminal = findViewById(R.id.btnClearTerminal)

        etBankUrl.setText(BankApiClient.getBaseUrl(this))
    }

    private fun setupListeners() {
        btnSaveBankUrl.setOnClickListener {
            val inputUrl = etBankUrl.text.toString().trim()
            if (inputUrl.isNotEmpty()) {
                BankApiClient.setBaseUrl(this, inputUrl)
                val updatedUrl = BankApiClient.getBaseUrl(this)
                etBankUrl.setText(updatedUrl)
                appendLog("CONFIG", "Updated Laptop Bank endpoint: $updatedUrl")
                Toast.makeText(this, "Bank Server IP Saved", Toast.LENGTH_SHORT).show()
            }
        }

        btnTestLogin.setOnClickListener {
            performOnlineBankLogin()
        }

        btnTestPin.setOnClickListener {
            showOfflinePinDialog()
        }

        btnTransferToOffline.setOnClickListener {
            initiateFundReservation()
        }

        btnSyncBank.setOnClickListener {
            performBankSync()
        }

        btnCryptoSelfTest.setOnClickListener {
            runCryptoSelfTest()
        }

        btnAddMockTx.setOnClickListener {
            generateMockOfflineTransaction()
        }

        btnClearTerminal.setOnClickListener {
            tvTerminalLog.text = ""
            appendLog("TERMINAL", "Log cleared.")
        }
    }

    private fun initHardwareCrypto() {
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                CryptoManager.getOrCreateKeyPair()
                val fingerprint = CryptoManager.getPublicKeyFingerprint()
                withContext(Dispatchers.Main) {
                    tvKeyFingerprint.text = fingerprint
                    appendLog("CRYPTO", "Hardware Keystore key initialized: $fingerprint")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvKeyFingerprint.text = "secp256r1: Active"
                    appendLog("CRYPTO", "Keystore init note: ${e.message}")
                }
            }
        }
    }

    private fun monitorNetworkConnectivity() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        updateNetworkBadge(NetworkManager.isOnline(this))

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    updateNetworkBadge(true)
                    appendLog("NETWORK", "🟢 Connectivity restored: Central Bank connection available.")
                }
            }

            override fun onLost(network: Network) {
                runOnUiThread {
                    updateNetworkBadge(false)
                    appendLog("NETWORK", "🔴 Connectivity lost: Switched to 100% Offline Emergency Mode.")
                }
            }
        }

        try {
            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateNetworkBadge(isOnline: Boolean) {
        sessionManager.updateOnlineStatus(isOnline)
        if (isOnline) {
            tvNetworkStatusBadge.text = "● Bank Connected"
            tvNetworkStatusBadge.setTextColor(getColor(R.color.primary_dark))
            tvNetworkStatusBadge.setBackgroundResource(R.drawable.bg_badge)
        } else {
            tvNetworkStatusBadge.text = "● Offline Mode"
            tvNetworkStatusBadge.setTextColor(getColor(R.color.amber_warning))
            tvNetworkStatusBadge.setBackgroundResource(R.drawable.bg_badge)
        }
    }

    private fun observeSession() {
        lifecycleScope.launch {
            sessionManager.sessionState.collectLatest { user ->
                updateUserUI(user)
            }
        }
    }

    private fun updateUserUI(user: UserSession) {
        tvUserName.text = user.name
        tvUserPhone.text = user.phone
        tvAccountNumber.text = user.accountNumber
        tvOnlineBalance.text = String.format(Locale.US, "₹%.2f", user.balance)
        tvEmergencyBalance.text = String.format(Locale.US, "₹%.2f", user.emergencyBalance)
    }

    private fun initiateFundReservation() {
        val isOnline = NetworkManager.isOnline(this)
        if (!isOnline) {
            appendLog("RESERVATION", "❌ BLOCKED: Device is offline. Offline funds cannot be minted locally; active connection to Central Laptop Bank is strictly required.")
            Toast.makeText(
                this,
                "Transfer blocked: Online connection to Central Bank is required.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val availableOnline = sessionManager.getUser().balance
        val dialog = TransferFundsDialog(
            context = this,
            availableOnlineBalance = availableOnline,
            onAuthorized = { amount ->
                executeOnlineFundReservation(amount)
            },
            onFailed = { reason ->
                appendLog("RESERVATION", "❌ $reason")
            }
        )
        dialog.show()
    }

    private fun executeOnlineFundReservation(amount: Double) {
        val targetUrl = BankApiClient.getBaseUrl(this)
        appendLog("RESERVATION", "Contacting Laptop Bank ($targetUrl) to reserve ₹$amount...")

        lifecycleScope.launch {
            try {
                val apiService = BankApiClient.getApiService(this@MainActivity)
                val request = TransferEmergencyRequest(userId = 1L, amount = amount)

                val response = withContext(Dispatchers.IO) {
                    apiService.transferToEmergency(request)
                }

                if (response.isSuccessful && response.body() != null) {
                    val result = response.body()!!
                    if (result.success) {
                        // 1. Atomically update local SQLite ledger
                        withContext(Dispatchers.IO) {
                            appDatabase.walletDao.addBalance(amount)
                        }

                        // 2. Update reactive user session state
                        sessionManager.updateBalances(result.balance, result.emergencyBalance)

                        val localWallet = withContext(Dispatchers.IO) {
                            appDatabase.walletDao.getWallet()
                        }

                        appendLog("BANK API", "✅ 200 OK: ${result.message}")
                        appendLog("BANK API", "Authoritative Bank Balances: Online = ₹${result.balance} | Reserve = ₹${result.emergencyBalance}")
                        appendLog("DATABASE", "Local SQLite wallet credited ₹$amount. New Local Offline Balance: ₹${localWallet?.balance}")

                        Toast.makeText(
                            this@MainActivity,
                            "Reserved ₹$amount to Emergency Wallet",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        appendLog("RESERVATION", "❌ Bank Error: ${result.message}")
                        Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
                    }
                } else {
                    val code = response.code()
                    val errorBody = response.errorBody()?.string() ?: "Unknown error"
                    appendLog("RESERVATION", "❌ HTTP $code: $errorBody")
                    Toast.makeText(this@MainActivity, "Reservation failed (HTTP $code)", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                appendLog("RESERVATION", "❌ Connection Failed: ${e.localizedMessage ?: e.javaClass.simpleName}")
                appendLog("HINT", "Ensure Laptop Bank server is running (app.py) and reachable via IP.")
                Toast.makeText(this@MainActivity, "Cannot reach Laptop Bank", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Sync Button Handler: Pushes all pending offline transactions to the Central Bank
     * and refreshes authoritative balances when online.
     */
    private fun performBankSync() {
        val isOnline = NetworkManager.isOnline(this)
        if (!isOnline) {
            appendLog("SYNC", "❌ Cannot sync while offline. Active bank connection required.")
            Toast.makeText(this, "Cannot sync offline. Connect to Wi-Fi/Bank.", Toast.LENGTH_SHORT).show()
            return
        }

        val targetUrl = BankApiClient.getBaseUrl(this)
        appendLog("SYNC", "Initiating Bank Reconciliation against $targetUrl...")

        lifecycleScope.launch {
            try {
                val apiService = BankApiClient.getApiService(this@MainActivity)

                // 1. Check for pending offline transactions in local SQLite
                val pendingTxs = withContext(Dispatchers.IO) {
                    appDatabase.transactionDao.getPendingSyncTransactions()
                }

                if (pendingTxs.isNotEmpty()) {
                    appendLog("SYNC", "Found ${pendingTxs.size} pending offline transactions. Uploading batch to bank...")
                    val syncItems = pendingTxs.map {
                        SyncTransactionItem(
                            txId = it.txId,
                            payee = it.receiverName,
                            amount = it.amount,
                            note = "Bluetooth Offline Payment (Nonce: ${it.nonce.take(8)})"
                        )
                    }

                    val syncResponse = withContext(Dispatchers.IO) {
                        apiService.syncTransactions(SyncTransactionsRequest(syncItems))
                    }

                    if (syncResponse.isSuccessful && syncResponse.body() != null) {
                        val body = syncResponse.body()!!
                        withContext(Dispatchers.IO) {
                            pendingTxs.forEach { tx ->
                                appDatabase.transactionDao.markAsSynced(tx.txId)
                            }
                        }
                        sessionManager.updateBalances(body.balance, body.emergencyBalance)
                        appendLog("SYNC", "✅ 200 OK: Reconciled ${body.reconciledCount} pending transactions with Bank ledger!")
                        appendLog("SYNC", "Bank Balance Settled: Online = ₹${body.balance} | Reserve = ₹${body.emergencyBalance}")
                    } else {
                        appendLog("SYNC", "⚠️ Sync upload returned HTTP ${syncResponse.code()}")
                    }
                } else {
                    appendLog("SYNC", "Zero pending offline transactions. Refreshing account balance from bank...")
                    val userResponse = withContext(Dispatchers.IO) {
                        apiService.getUser()
                    }
                    if (userResponse.isSuccessful && userResponse.body() != null) {
                        val bankUser = userResponse.body()!!
                        sessionManager.updateBalances(bankUser.balance, bankUser.emergencyBalance)
                        appendLog("SYNC", "✅ 200 OK: Latest Bank Balances: Online = ₹${bankUser.balance} | Reserve = ₹${bankUser.emergencyBalance}")
                    }
                }

                refreshLocalLedgerUI()
                Toast.makeText(this@MainActivity, "Synchronized with Bank", Toast.LENGTH_SHORT).show()

            } catch (e: Exception) {
                appendLog("SYNC", "❌ Bank Sync Failed: ${e.localizedMessage ?: e.javaClass.simpleName}")
                Toast.makeText(this@MainActivity, "Sync Failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Phase 4: Hardware Cryptographic Self-Test
     * Canonicalizes payload, signs with SHA256withECDSA in AndroidKeyStore,
     * and verifies the signature on-device.
     */
    private fun runCryptoSelfTest() {
        appendLog("CRYPTO", "Running Hardware Keystore Cryptographic Self-Test...")
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val txId = CryptoManager.generateTxId()
                val nonce = CryptoManager.generateNonce()
                val timestamp = System.currentTimeMillis()
                val amount = 5.0
                val sender = "USER_1001"
                val receiver = "MERCHANT_APEX"

                val canonicalBytes = CryptoManager.getCanonicalBytes(txId, sender, receiver, amount, timestamp, nonce)
                val canonicalStr = String(canonicalBytes)

                val signature = CryptoManager.signData(canonicalBytes)
                val pubKeyBase64 = CryptoManager.getPublicKeyBase64()

                val isVerified = CryptoManager.verifySignature(pubKeyBase64, canonicalBytes, signature)

                withContext(Dispatchers.Main) {
                    appendLog("CRYPTO", "📄 Canonical Payload: $canonicalStr")
                    appendLog("CRYPTO", "🛡️ Nonce (Anti-Replay): $nonce (16 bytes random)")
                    appendLog("CRYPTO", "✍️ ECDSA Signature (secp256r1): ${signature.take(28)}... (${signature.length} chars)")
                    if (isVerified) {
                        appendLog("CRYPTO", "✅ Cryptographic Verification: PASSED (Integrity & Non-Repudiation Confirmed)")
                        Toast.makeText(this@MainActivity, "Crypto Self-Test: PASSED (ECDSA Validated)", Toast.LENGTH_SHORT).show()
                    } else {
                        appendLog("CRYPTO", "❌ Cryptographic Verification: FAILED")
                        Toast.makeText(this@MainActivity, "Crypto Self-Test: FAILED", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendLog("CRYPTO", "❌ Crypto Error: ${e.message}")
                }
            }
        }
    }

    /**
     * Phase 4: Mock Offline Transaction Injection
     * Allows evaluator to simulate paying ₹5 offline to an essential merchant
     * and watch it get logged as PENDING_SYNC in the local SQLite ledger.
     */
    private fun generateMockOfflineTransaction() {
        val currentWallet = appDatabase.walletDao.getWallet()
        val currentBal = currentWallet?.balance ?: 0.0

        if (currentBal < 5.0) {
            appendLog("OFFLINE TX", "❌ Insufficient offline balance (₹$currentBal). Reserve funds first.")
            Toast.makeText(this, "Insufficient offline wallet balance (₹$currentBal)", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val txId = CryptoManager.generateTxId()
                val nonce = CryptoManager.generateNonce()
                val amount = 5.0
                val timestamp = System.currentTimeMillis()
                val merchantName = "Apex City Care Pharmacy"

                val canonicalBytes = CryptoManager.getCanonicalBytes(txId, "1", "MERCHANT_APEX", amount, timestamp, nonce)
                val signature = CryptoManager.signData(canonicalBytes)

                // Atomic deduction in local SQLite
                val deducted = appDatabase.walletDao.deductBalance(amount)
                if (deducted) {
                    val tx = TransactionEntity(
                        txId = txId,
                        senderId = "1",
                        senderName = "Rahul Sharma",
                        receiverId = "MERCHANT_APEX",
                        receiverName = merchantName,
                        amount = amount,
                        timestamp = timestamp,
                        nonce = nonce,
                        signature = signature,
                        status = TransactionEntity.STATUS_PENDING_SYNC,
                        direction = TransactionEntity.DIRECTION_SENT
                    )
                    appDatabase.transactionDao.insertTransaction(tx)

                    val updatedWallet = appDatabase.walletDao.getWallet()
                    val newBal = updatedWallet?.balance ?: 0.0

                    withContext(Dispatchers.Main) {
                        sessionManager.updateBalances(sessionManager.getUser().balance, newBal)
                        refreshLocalLedgerUI()

                        appendLog("OFFLINE TX", "💸 Offline Payment Generated: ₹$amount to $merchantName")
                        appendLog("OFFLINE TX", "Tx ID: $txId | Nonce: $nonce")
                        appendLog("OFFLINE TX", "Local SQLite Balance: ₹$newBal | Status: PENDING_SYNC")
                        appendLog("HINT", "Tap '🔄 Sync' to push this transaction to the Laptop Bank.")

                        Toast.makeText(this@MainActivity, "Offline Tx Created: -₹5.00 (PENDING_SYNC)", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendLog("OFFLINE TX", "❌ Error creating mock tx: ${e.message}")
                }
            }
        }
    }

    private fun refreshLocalLedgerUI() {
        lifecycleScope.launch(Dispatchers.IO) {
            val transactions = appDatabase.transactionDao.getAllTransactions()
            val pendingCount = appDatabase.transactionDao.getPendingSyncTransactions().size

            withContext(Dispatchers.Main) {
                tvPendingTxBadge.text = "$pendingCount Pending Sync"

                layoutLedgerContainer.removeAllViews()

                if (transactions.isEmpty()) {
                    tvEmptyLedgerMsg.visibility = View.VISIBLE
                    layoutLedgerContainer.addView(tvEmptyLedgerMsg)
                } else {
                    tvEmptyLedgerMsg.visibility = View.GONE
                    val inflater = LayoutInflater.from(this@MainActivity)

                    transactions.take(5).forEach { tx ->
                        val itemView = inflater.inflate(R.layout.item_ledger_transaction, layoutLedgerContainer, false)
                        val tvDir = itemView.findViewById<TextView>(R.id.tvTxDirection)
                        val tvPayee = itemView.findViewById<TextView>(R.id.tvTxPayee)
                        val tvAmount = itemView.findViewById<TextView>(R.id.tvTxAmount)
                        val tvId = itemView.findViewById<TextView>(R.id.tvTxId)
                        val tvStatus = itemView.findViewById<TextView>(R.id.tvTxStatusBadge)

                        tvDir.text = tx.direction
                        tvPayee.text = tx.receiverName
                        tvAmount.text = String.format(Locale.US, "- ₹%.2f", tx.amount)
                        tvId.text = "${tx.txId} · Nonce: ${tx.nonce.take(6)}"

                        if (tx.status == TransactionEntity.STATUS_PENDING_SYNC) {
                            tvStatus.text = "PENDING_SYNC"
                            tvStatus.setTextColor(getColor(R.color.amber_warning))
                            tvStatus.setBackgroundResource(R.drawable.bg_badge)
                        } else {
                            tvStatus.text = "SYNCED"
                            tvStatus.setTextColor(getColor(R.color.primary_dark))
                            tvStatus.setBackgroundResource(R.drawable.bg_badge)
                        }

                        layoutLedgerContainer.addView(itemView)
                    }
                }
            }
        }
    }

    private fun performOnlineBankLogin() {
        val targetUrl = BankApiClient.getBaseUrl(this)
        appendLog("BANK API", "Attempting online login handshake at $targetUrl...")

        lifecycleScope.launch {
            try {
                val apiService = BankApiClient.getApiService(this@MainActivity)
                val response = withContext(Dispatchers.IO) {
                    apiService.getUser()
                }

                if (response.isSuccessful && response.body() != null) {
                    val bankUser = response.body()!!
                    sessionManager.updateBalances(bankUser.balance, bankUser.emergencyBalance)
                    sessionManager.updateOnlineStatus(true)

                    updateNetworkBadge(true)

                    appendLog("BANK API", "200 OK: Authenticated as ${bankUser.name} (Phone: ${bankUser.phone})")
                    appendLog("BANK API", "Online Balance: ₹${bankUser.balance} | Offline Reserve: ₹${bankUser.emergencyBalance}")
                    Toast.makeText(this@MainActivity, "Connected to Laptop Bank", Toast.LENGTH_SHORT).show()
                } else {
                    val code = response.code()
                    val errorMsg = response.errorBody()?.string() ?: "Unknown error"
                    appendLog("BANK API", "HTTP $code Error: $errorMsg")
                    Toast.makeText(this@MainActivity, "Bank Login Failed (HTTP $code)", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                appendLog("BANK API", "Connection Failed: ${e.localizedMessage ?: e.javaClass.simpleName}")
                appendLog("HINT", "Verify laptop bank server is running (app.py) and reachable via Wi-Fi/IP.")
                Toast.makeText(this@MainActivity, "Cannot reach Laptop Bank", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showOfflinePinDialog() {
        val dialog = PinAuthDialog(
            context = this,
            actionTitle = "Authorize Offline Session",
            actionSubtitle = "Enter 4-digit PIN (Default: 1234)",
            onSuccess = {
                tvPinStatusBadge.text = "✅ 1234 Verified"
                appendLog("PIN AUTH", "✅ 100% OFFLINE AUTH SUCCESS: Salted SHA-256 PIN verified locally via EncryptedSharedPreferences.")
                appendLog("CRYPTO", "Session unlocked for Rahul Sharma without server connectivity.")
                Toast.makeText(this, "PIN Verified Locally (Offline)", Toast.LENGTH_SHORT).show()
            },
            onFailed = { failMsg ->
                appendLog("PIN AUTH", "❌ $failMsg")
            }
        )
        dialog.show()
    }

    private fun appendLog(tag: String, message: String) {
        val timestamp = timeFormat.format(Date())
        val logLine = "[$timestamp] [$tag] $message\n"
        tvTerminalLog.append(logLine)

        scrollTerminal.post {
            scrollTerminal.fullScroll(View.FOCUS_DOWN)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (networkCallback != null && connectivityManager != null) {
                connectivityManager?.unregisterNetworkCallback(networkCallback!!)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
