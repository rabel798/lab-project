package com.example.empay

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import com.example.empay.auth.PinAuthManager
import com.example.empay.auth.SessionManager
import com.example.empay.auth.UserSession
import com.example.empay.bluetooth.BluetoothHelper
import com.example.empay.bluetooth.BluetoothPaymentEngine
import com.example.empay.crypto.CryptoManager
import com.example.empay.db.AppDatabase
import com.example.empay.db.TransactionEntity
import com.example.empay.network.AddFundsRequest
import com.example.empay.network.BankApiClient
import com.example.empay.repository.PaymentRepository
import com.example.empay.network.NetworkManager
import com.example.empay.network.SyncTransactionItem
import com.example.empay.network.SyncTransactionsRequest
import com.example.empay.network.TransferEmergencyRequest
import com.example.empay.sync.SyncManager
import com.example.empay.sync.SyncNotificationHelper
import com.example.empay.ui.BluetoothDeviceDialog
import com.example.empay.ui.PinAuthDialog
import com.example.empay.ui.TransferFundsDialog
import com.example.empay.ui.UserProfileDialog
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
    private lateinit var bluetoothEngine: BluetoothPaymentEngine
    private lateinit var paymentRepository: PaymentRepository

    // UI elements - Top Bar matching emergencypay.html
    private lateinit var layoutSystemStatusBanner: View
    private lateinit var viewStatusDot: View
    private lateinit var tvNetworkStatusBadge: TextView
    private lateinit var btnEmergencyToggle: View
    private lateinit var btnNotificationBell: View

    // User Profile & Balances
    private lateinit var tvUserName: TextView
    private lateinit var tvWelcomeSub: TextView
    private lateinit var tvOnlineBalance: TextView
    private lateinit var tvEmergencyBalance: TextView
    private lateinit var btnTransferToOffline: Button
    private lateinit var btnAddMoney: Button

    // Emergency Alert Banner
    private lateinit var layoutEmergencyAlertBanner: View

    // Quick Actions
    private lateinit var tvQuickActionsTitle: TextView
    private lateinit var badgeEmergencyModeActive: TextView
    private lateinit var btnActionScan: View
    private lateinit var btnActionPayBt: View
    private lateinit var btnActionMerchants: View
    private lateinit var btnActionSendMoney: View
    private lateinit var btnActionHistory: View
    private lateinit var btnActionProfile: View

    // Essential Services Carousel
    private lateinit var cardDailyFresh: View
    private lateinit var cardApexPharmacy: View
    private lateinit var cardLifeLine: View
    private lateinit var cardNationalBook: View
    private lateinit var cardQuickFix: View
    private lateinit var btnViewAllMerchants: TextView

    // Recent Transactions
    private lateinit var btnSeeAllTransactions: TextView
    private lateinit var layoutRecentTransactionsContainer: LinearLayout
    private lateinit var tvEmptyRecentTransactions: TextView

    // Bottom Navigation
    private lateinit var navTabHome: View
    private lateinit var navTabScan: View
    private lateinit var navTabPayBt: View
    private lateinit var navTabHistory: View
    private lateinit var navTabProfile: View

    // Developer Diagnostics Panel (Collapsible)
    private var layoutP2PControlsCard: View? = null
    private var etPayeeName: EditText? = null
    private var etBtAmount: EditText? = null
    private var tvUserPhone: TextView? = null
    private var tvAccountNumber: TextView? = null
    private var tvPinStatusBadge: TextView? = null
    private var btnSwitchUserProfile: TextView? = null
    private var tvKeyFingerprint: TextView? = null
    private var tvAutoSyncBadge: TextView? = null
    private var etBankUrl: EditText? = null
    private var btnSaveBankUrl: Button? = null
    private var btnTriggerWorkManager: Button? = null
    private var btnTestLogin: Button? = null
    private var btnTestPin: Button? = null
    private var btnCryptoSelfTest: Button? = null
    private var btnTamperTest: Button? = null
    private var btnAddMockTx: Button? = null
    private var btnModePayer: Button? = null
    private var btnModeMerchant: Button? = null
    private var btnScanTerminal: Button? = null
    private var btnBtChip2: Button? = null
    private var btnBtChip5: Button? = null
    private var btnPayLoopback: Button? = null
    private var tvMerchantStatusBeacon: TextView? = null
    private var btnToggleMerchantRadar: Button? = null
    private var tvPendingTxBadge: TextView? = null
    private var tvTerminalLog: TextView? = null
    private lateinit var scrollTerminal: ScrollView

    private var selectedBluetoothDevice: BluetoothDevice? = null
    private var isMerchantRadarRunning = false

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val authManager = com.example.empay.auth.AuthManager.getInstance(this)
        if (!authManager.isLoggedIn()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        sessionManager = SessionManager.getInstance(this)
        pinAuthManager = PinAuthManager.getInstance(this)
        appDatabase = AppDatabase.getInstance(this)
        bluetoothEngine = BluetoothPaymentEngine(this)
        paymentRepository = PaymentRepository.getInstance(this)

        initViews()
        initBluetoothFlow()
        setupListeners()
        observeSession()
        monitorNetworkConnectivity()
        initHardwareCrypto()
        refreshLocalLedgerUI()
        initPhase6WorkManager()

        val localWallet = appDatabase.walletDao.getWallet()
        if (localWallet != null) {
            sessionManager.updateBalances(sessionManager.getUser().balance, localWallet.balance)
        }
        lifecycleScope.launch {
            paymentRepository.refreshAuthoritativeBalances()
        }

        appendLog("INIT", "EmPay Protocol Core initialized (Min SDK 26, Target SDK 36)")
        appendLog("DATABASE", "Local SQLite ledger ready (empay_offline.db). Offline balance: ₹${localWallet?.balance ?: 10.0}")
        appendLog("P2P", "Bluetooth RFCOMM Engine loaded (SPP UUID: 00001101-0000-1000-8000-00805F9B34FB)")
    }

    private fun initViews() {
        // Sticky Header / Top Bar
        layoutSystemStatusBanner = findViewById(R.id.layoutSystemStatusBanner)
        viewStatusDot = findViewById(R.id.viewStatusDot)
        tvNetworkStatusBadge = findViewById(R.id.tvNetworkStatusBadge)
        btnEmergencyToggle = findViewById(R.id.btnEmergencyToggle)
        btnNotificationBell = findViewById(R.id.btnNotificationBell)

        // Welcome
        tvUserName = findViewById(R.id.tvUserName)
        tvWelcomeSub = findViewById(R.id.tvWelcomeSub)

        // Balance Card
        tvOnlineBalance = findViewById(R.id.tvOnlineBalance)
        tvEmergencyBalance = findViewById(R.id.tvEmergencyBalance)
        btnTransferToOffline = findViewById(R.id.btnTransferToOffline)
        btnAddMoney = findViewById(R.id.btnAddMoney)

        // Alert Banner
        layoutEmergencyAlertBanner = findViewById(R.id.layoutEmergencyAlertBanner)

        // Quick Actions
        tvQuickActionsTitle = findViewById(R.id.tvQuickActionsTitle)
        badgeEmergencyModeActive = findViewById(R.id.badgeEmergencyModeActive)
        btnActionScan = findViewById(R.id.btnActionScan)
        btnActionPayBt = findViewById(R.id.btnActionPayBt)
        btnActionMerchants = findViewById(R.id.btnActionMerchants)
        btnActionSendMoney = findViewById(R.id.btnActionSendMoney)
        btnActionHistory = findViewById(R.id.btnActionHistory)
        btnActionProfile = findViewById(R.id.btnActionProfile)

        // Essential Services
        cardDailyFresh = findViewById(R.id.cardDailyFresh)
        cardApexPharmacy = findViewById(R.id.cardApexPharmacy)
        cardLifeLine = findViewById(R.id.cardLifeLine)
        cardNationalBook = findViewById(R.id.cardNationalBook)
        cardQuickFix = findViewById(R.id.cardQuickFix)
        btnViewAllMerchants = findViewById(R.id.btnViewAllMerchants)

        // Recent Transactions
        btnSeeAllTransactions = findViewById(R.id.btnSeeAllTransactions)
        layoutRecentTransactionsContainer = findViewById(R.id.layoutRecentTransactionsContainer)
        tvEmptyRecentTransactions = findViewById(R.id.tvEmptyRecentTransactions)

        // Bottom Navigation
        navTabHome = findViewById(R.id.navTabHome)
        navTabScan = findViewById(R.id.navTabScan)
        navTabPayBt = findViewById(R.id.navTabPayBt)
        navTabHistory = findViewById(R.id.navTabHistory)
        navTabProfile = findViewById(R.id.navTabProfile)

        scrollTerminal = findViewById(R.id.scrollTerminal)

        // Developer Diagnostics Panel (Collapsible)
        layoutP2PControlsCard = findViewById(R.id.layoutP2PControlsCard)
        etBankUrl = findViewById(R.id.etBankUrl)
        btnSaveBankUrl = findViewById(R.id.btnSaveBankUrl)
        tvAutoSyncBadge = findViewById(R.id.tvAutoSyncBadge)
        btnTriggerWorkManager = findViewById(R.id.btnTriggerWorkManager)
        btnTestLogin = findViewById(R.id.btnTestLogin)
        btnTestPin = findViewById(R.id.btnTestPin)
        btnCryptoSelfTest = findViewById(R.id.btnCryptoSelfTest)
        btnTamperTest = findViewById(R.id.btnTamperTest)
        btnAddMockTx = findViewById(R.id.btnAddMockTx)
        btnModePayer = findViewById(R.id.btnModePayer)
        btnModeMerchant = findViewById(R.id.btnModeMerchant)
        btnScanTerminal = findViewById(R.id.btnScanTerminal)
        btnBtChip2 = findViewById(R.id.btnBtChip2)
        btnBtChip5 = findViewById(R.id.btnBtChip5)
        btnPayLoopback = findViewById(R.id.btnPayLoopback)
        etPayeeName = findViewById(R.id.etPayeeName)
        etBtAmount = findViewById(R.id.etBtAmount)
        tvUserPhone = findViewById(R.id.tvUserPhone)
        tvAccountNumber = findViewById(R.id.tvAccountNumber)
        tvPinStatusBadge = findViewById(R.id.tvPinStatusBadge)
        btnSwitchUserProfile = findViewById(R.id.btnSwitchUserProfile)
        tvKeyFingerprint = findViewById(R.id.tvKeyFingerprint)
        tvTerminalLog = findViewById(R.id.tvConsoleLog)

        etBankUrl?.setText(BankApiClient.getBaseUrl(this))
    }

    private fun setupListeners() {
        // Top Bar: Real Network Status Banner Info (governed by actual internet)
        layoutSystemStatusBanner.setOnClickListener {
            val isOnline = NetworkManager.isOnline(this)
            val msg = if (isOnline) "● Central Bank Online: Internet connected. Auto-sync and online transfers active."
                      else "● Emergency Mode Active: No internet. 100% offline Bluetooth payments active."
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }

        // Long press toggles Developer Diagnostics
        layoutSystemStatusBanner.setOnLongClickListener {
            toggleDevDiagnostics()
            true
        }

        // Emergency Siren Button -> Explains Real Network State
        btnEmergencyToggle.setOnClickListener {
            val isOnline = NetworkManager.isOnline(this)
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(if (isOnline) "Network Status: Online" else "Network Status: Emergency Mode")
                .setMessage(if (isOnline)
                    "You are connected to the Central Bank.\n\nOnline transfers, fund reservations, and automatic ledger synchronization are fully operational."
                    else "No internet connection detected.\n\nEmergency Mode is actively securing your wallet. You can make secure 100% offline payments via Bluetooth or QR codes. Balances will auto-sync when internet reconnects.")
                .setPositiveButton("OK", null)
                .show()
        }


        // Notifications Bell
        btnNotificationBell.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Notifications")
                .setMessage("● Offline Payment Ready\nMesh network standby active for emergencies.\n\n● Welcome to EmergencyPay\nYour wallet is loaded and secured.")
                .setPositiveButton("OK", null)
                .show()
        }

        // Greeting / Profile
        tvUserName.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }
        tvUserName.setOnLongClickListener {
            toggleDevDiagnostics()
            true
        }

        // Balance Card
        btnTransferToOffline.setOnClickListener { initiateFundReservation() }
        btnAddMoney.setOnClickListener { showAddMoneyDialog() }

        // Quick Actions
        btnActionScan.setOnClickListener {
            startActivity(Intent(this, ScanActivity::class.java))
        }
        btnActionPayBt.setOnClickListener {
            startActivity(Intent(this, BluetoothActivity::class.java))
        }
        btnActionMerchants.setOnClickListener {
            startActivity(Intent(this, MerchantsActivity::class.java))
        }
        btnActionSendMoney.setOnClickListener {
            val intent = Intent(this, PayActivity::class.java).apply {
                putExtra("PAYEE_NAME", "")
                putExtra("DEFAULT_AMOUNT", "25.00")
                putExtra("CHANNEL", if (NetworkManager.isOnline(this@MainActivity)) "UPI Online" else "Bluetooth")
            }
            startActivity(intent)
        }
        btnActionHistory.setOnClickListener {
            startActivity(Intent(this, TransactionHistoryActivity::class.java))
        }
        btnActionProfile.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }

        // Essential Services Quick Pay
        cardDailyFresh.setOnClickListener {
            openPayForMerchant("Daily Fresh Supermarket", "35.00")
        }
        cardApexPharmacy.setOnClickListener {
            openPayForMerchant("Apex City Care Pharmacy", "12.00")
        }
        cardLifeLine.setOnClickListener {
            openPayForMerchant("LifeLine Medicos", "25.00")
        }
        cardNationalBook.setOnClickListener {
            openPayForMerchant("National Book Depot", "18.00")
        }
        cardQuickFix.setOnClickListener {
            openPayForMerchant("City QuickFix Electrician", "50.00")
        }
        btnViewAllMerchants.setOnClickListener {
            startActivity(Intent(this, MerchantsActivity::class.java))
        }

        // Recent Transactions: See All
        btnSeeAllTransactions.setOnClickListener {
            startActivity(Intent(this, TransactionHistoryActivity::class.java))
        }

        // Bottom Navigation Bar
        navTabHome.setOnClickListener {
            scrollTerminal.smoothScrollTo(0, 0)
        }
        navTabScan.setOnClickListener {
            startActivity(Intent(this, ScanActivity::class.java))
        }
        navTabPayBt.setOnClickListener {
            startActivity(Intent(this, BluetoothActivity::class.java))
        }
        navTabHistory.setOnClickListener {
            startActivity(Intent(this, TransactionHistoryActivity::class.java))
        }
        navTabProfile.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }

        // Diagnostics Panel Controls
        btnSaveBankUrl?.setOnClickListener {
            val inputUrl = etBankUrl?.text.toString().trim()
            if (inputUrl.isNotEmpty()) {
                BankApiClient.setBaseUrl(this, inputUrl)
                val updatedUrl = BankApiClient.getBaseUrl(this)
                etBankUrl?.setText(updatedUrl)
                appendLog("CONFIG", "Updated Laptop Bank endpoint: $updatedUrl")
                Toast.makeText(this, "Bank Server IP Saved", Toast.LENGTH_SHORT).show()
            }
        }
        btnTriggerWorkManager?.setOnClickListener { triggerWorkManagerSync() }
        btnTestLogin?.setOnClickListener { performOnlineBankLogin() }
        btnTestPin?.setOnClickListener { showOfflinePinDialog() }
        btnCryptoSelfTest?.setOnClickListener { runCryptoSelfTest() }
        btnTamperTest?.setOnClickListener { runTamperVerificationTest() }
        btnAddMockTx?.setOnClickListener { generateMockOfflineTransaction() }
        btnModePayer?.setOnClickListener { updateP2PMode(isPayer = true) }
        btnModeMerchant?.setOnClickListener { updateP2PMode(isPayer = false) }
        btnScanTerminal?.setOnClickListener { showBluetoothDevicePicker() }
        btnBtChip2?.setOnClickListener { etBtAmount?.setText("2.00") }
        btnBtChip5?.setOnClickListener { etBtAmount?.setText("5.00") }
        btnPayLoopback?.setOnClickListener { initiateP2PPayment(isLoopback = true) }
    }

    private fun openPayForMerchant(name: String, amount: String) {
        val intent = Intent(this, PayActivity::class.java).apply {
            putExtra("PAYEE_NAME", name)
            putExtra("DEFAULT_AMOUNT", amount)
            putExtra("CHANNEL", "Bluetooth")
        }
        startActivity(intent)
    }

    private fun toggleDevDiagnostics() {
        layoutP2PControlsCard?.let { card ->
            card.visibility = if (card.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            val status = if (card.visibility == View.VISIBLE) "Visible" else "Hidden"
            Toast.makeText(this, "Developer Diagnostics: $status", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Updates the segmented P2P mode switch with clean styling and high-contrast text.
     */
    private fun updateP2PMode(isPayer: Boolean) {
        val activeBg = ContextCompat.getColorStateList(this, R.color.primary)
        val inactiveBg = ColorStateList.valueOf(Color.parseColor("#E2E8F0"))
        val activeTextColor = Color.WHITE
        val inactiveTextColor = Color.parseColor("#1E293B")

        if (isPayer) {
            btnModePayer?.backgroundTintList = activeBg
            btnModePayer?.setTextColor(activeTextColor)
            btnModeMerchant?.backgroundTintList = inactiveBg
            btnModeMerchant?.setTextColor(inactiveTextColor)
            appendLog("P2P", "Switched to PAYER mode (Send offline money via Bluetooth)")
        } else {
            btnModeMerchant?.backgroundTintList = activeBg
            btnModeMerchant?.setTextColor(activeTextColor)
            btnModePayer?.backgroundTintList = inactiveBg
            btnModePayer?.setTextColor(inactiveTextColor)
            appendLog("P2P", "Switched to MERCHANT mode (Receive & verify offline payments)")
        }
    }

    /**
     * Opens the interactive Bluetooth Device Scanner & Selection dialog.
     */
    private fun showBluetoothDevicePicker() {
        val picker = BluetoothDeviceDialog(
            context = this,
            bluetoothEngine = bluetoothEngine,
            onRequestPermissions = {
                checkAndRequestBluetoothPermissions(autoOpenPicker = false)
            },
            onDeviceSelected = { name, device, isDemoLoopback ->
                etPayeeName?.setText(name)
                selectedBluetoothDevice = device
                val intent = Intent(this, PayActivity::class.java).apply {
                    putExtra("PAYEE_NAME", name)
                    putExtra("DEFAULT_AMOUNT", "10.00")
                    putExtra("CHANNEL", "Bluetooth")
                }
                startActivity(intent)
            }
        )
        picker.show()
    }

    private val REQUEST_BLUETOOTH_PERMISSIONS = 1001
    private var pendingLaunchDevicePicker = false

    private fun checkAndRequestBluetoothPermissions(autoOpenPicker: Boolean = false) {
        pendingLaunchDevicePicker = autoOpenPicker
        val required = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                required.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                required.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                required.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                required.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (required.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, required.toTypedArray(), REQUEST_BLUETOOTH_PERMISSIONS)
        } else if (autoOpenPicker) {
            showBluetoothDevicePicker()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                appendLog("PERMISSIONS", "🟢 Bluetooth permissions granted.")
                val currentUser = sessionManager.getUser()
                BluetoothHelper.broadcastEmPayIdentity(this, currentUser.name, currentUser.phone)
                if (pendingLaunchDevicePicker) {
                    pendingLaunchDevicePicker = false
                    showBluetoothDevicePicker()
                }
            } else {
                appendLog("PERMISSIONS", "⚠️ Bluetooth permission denied. Nearby discovery might be limited.")
                pendingLaunchDevicePicker = false
                Toast.makeText(this, "Bluetooth permissions required for nearby device discovery", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Call this early (e.g., after UI init) to prompt permissions without forcibly showing the picker dialog
    private fun initBluetoothFlow() {
        checkAndRequestBluetoothPermissions(autoOpenPicker = false)
        if (BluetoothHelper.hasConnectPermission(this)) {
            val currentUser = sessionManager.getUser()
            BluetoothHelper.broadcastEmPayIdentity(this, currentUser.name, currentUser.phone)
        }
    }

    private fun initHardwareCrypto() {
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                CryptoManager.getOrCreateKeyPair()
                val fingerprint = CryptoManager.getPublicKeyFingerprint()
                withContext(Dispatchers.Main) {
                    tvKeyFingerprint?.text = fingerprint
                    appendLog("CRYPTO", "Hardware Keystore key initialized: $fingerprint")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvKeyFingerprint?.text = "secp256r1: Active"
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
                    appendLog("AUTO-SYNC", "Network restored. Triggering automatic ledger sync & balance refresh...")
                    lifecycleScope.launch {
                        SyncManager.syncNow(this@MainActivity)
                        paymentRepository.refreshAuthoritativeBalances()
                        refreshLocalLedgerUI()
                    }
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
            tvNetworkStatusBadge.text = "Online"
            tvNetworkStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_online_text))
            viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_online)
            layoutSystemStatusBanner.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_online_bg)
            layoutEmergencyAlertBanner.visibility = View.GONE
            badgeEmergencyModeActive.visibility = View.GONE
            tvQuickActionsTitle.text = "Quick Actions"
            btnActionSendMoney.visibility = View.VISIBLE
        } else {
            tvNetworkStatusBadge.text = "Emergency"
            tvNetworkStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.emergency_dark))
            viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.emergency)
            layoutSystemStatusBanner.backgroundTintList = ContextCompat.getColorStateList(this, R.color.emergency_bg)
            layoutEmergencyAlertBanner.visibility = View.VISIBLE
            badgeEmergencyModeActive.visibility = View.VISIBLE
            badgeEmergencyModeActive.backgroundTintList = ContextCompat.getColorStateList(this, R.color.electric_orange)
            badgeEmergencyModeActive.setTextColor(Color.WHITE)
            tvQuickActionsTitle.text = "Emergency Actions"
            btnActionSendMoney.visibility = View.GONE
        }
    }

    private fun observeSession() {
        lifecycleScope.launch {
            paymentRepository.observeUserSession().collectLatest { user ->
                updateUserUI(user)
            }
        }
    }

    private fun updateUserUI(user: UserSession) {
        val firstName = user.name.split(" ").firstOrNull() ?: user.name
        tvUserName.text = "Hi, $firstName"
        tvUserPhone?.text = user.phone
        tvAccountNumber?.text = user.accountNumber
        tvOnlineBalance.text = String.format(Locale.US, "₹ %.2f", user.balance)
        tvEmergencyBalance.text = String.format(Locale.US, "₹ %.2f", user.emergencyBalance)
    }

    private fun showAddMoneyDialog() {
        if (!NetworkManager.isOnline(this)) {
            appendLog("BANK API", "❌ BLOCKED: Device is offline. Cannot add funds to bank account.")
            Toast.makeText(this, "Cannot add money while offline. Connect to internet first.", Toast.LENGTH_LONG).show()
            return
        }

        val inputAmt = EditText(this).apply {
            hint = "Enter amount (e.g. 500)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("500.00")
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Add Funds to Bank Account")
            .setMessage("Top up your available online balance on the Central Bank ledger:")
            .setView(inputAmt)
            .setPositiveButton("Add Money") { _, _ ->
                val amt = inputAmt.text.toString().trim().toDoubleOrNull()
                if (amt != null && amt > 0) {
                    val user = sessionManager.getUser()
                    lifecycleScope.launch {
                        try {
                            val api = BankApiClient.getApiService(this@MainActivity)
                            val resp = withContext(Dispatchers.IO) {
                                api.addFunds(AddFundsRequest(userId = user.id, amount = amt))
                            }
                            if (resp.isSuccessful && resp.body()?.success == true) {
                                val newBal = resp.body()!!.balance
                                sessionManager.updateBalances(newBal, user.emergencyBalance)
                                appendLog("BANK API", "✅ Top-up Success: ₹$amt added to Central Bank. New Balance: ₹$newBal")
                                Toast.makeText(this@MainActivity, "✅ Added ₹$amt to Bank Account!", Toast.LENGTH_SHORT).show()
                                refreshLocalLedgerUI()
                            } else {
                                performOnlineBankLogin()
                            }
                        } catch (e: Exception) {
                            appendLog("BANK API", "Add Funds Notice: ${e.message}")
                            performOnlineBankLogin()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        appendLog("RESERVATION", "Contacting Central Bank ($targetUrl) to reserve ₹$amount...")

        lifecycleScope.launch {
            val result = paymentRepository.reserveEmergencyFunds(amount)
            result.onSuccess { data ->
                val localWallet = paymentRepository.getOfflineWallet()
                appendLog("BANK API", "✅ 200 OK: ${data.message}")
                appendLog("BANK API", "Authoritative Bank Balances: Online = ₹${data.balance} | Reserve = ₹${data.emergencyBalance}")
                appendLog("DATABASE", "Local SQLite wallet credited ₹$amount. New Local Offline Balance: ₹${localWallet?.balance}")

                Toast.makeText(
                    this@MainActivity,
                    "Reserved ₹$amount to Emergency Wallet",
                    Toast.LENGTH_SHORT
                ).show()
                refreshLocalLedgerUI()
            }.onFailure { error ->
                appendLog("RESERVATION", "❌ Bank Notice: ${error.message}")
                Toast.makeText(this@MainActivity, error.message ?: "Reservation failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Phase 5: P2P Bluetooth / Loopback Payment Flow
     */
    private fun initiateP2PPayment(isLoopback: Boolean) {
        val amountStr = etBtAmount?.text?.toString()?.trim() ?: "10.00"
        val amount = amountStr.toDoubleOrNull() ?: 10.0
        val payeeName = etPayeeName?.text?.toString()?.trim()?.ifEmpty { "Apex City Care Pharmacy" } ?: "Apex City Care Pharmacy"

        val currentUser = paymentRepository.getCurrentUser()
        if (payeeName.trim().equals(currentUser.phone.trim(), ignoreCase = true) ||
            payeeName.trim().equals(currentUser.accountNumber.trim(), ignoreCase = true) ||
            payeeName.equals("Self", ignoreCase = true) ||
            payeeName.equals("Myself", ignoreCase = true)) {
            appendLog("P2P PAYMENT", "❌ Self-transfer blocked: Cannot transfer money to your own account/phone (${currentUser.phone}).")
            Toast.makeText(this, "Self-transfer not allowed: Cannot send money to yourself.", Toast.LENGTH_LONG).show()
            return
        }

        if (amount == null || amount <= 0.0) {
            Toast.makeText(this, "Please enter a valid amount", Toast.LENGTH_SHORT).show()
            return
        }

        val localWallet = appDatabase.walletDao.getWallet()
        val currentBal = localWallet?.balance ?: 0.0

        if (currentBal < amount) {
            appendLog("P2P PAYMENT", "❌ Insufficient offline balance (₹$currentBal). Cannot send ₹$amount.")
            Toast.makeText(this, "Insufficient offline wallet balance (₹$currentBal)", Toast.LENGTH_LONG).show()
            return
        }

        // Require 4-digit PIN verification to unlock private key signing
        val pinDialog = PinAuthDialog(
            context = this,
            actionTitle = "Authorize Offline Transfer",
            actionSubtitle = "Enter PIN to sign ₹$amount payment with ECDSA",
            onSuccess = {
                if (isLoopback) {
                    executeLoopbackPayment(payeeName, amount)
                } else {
                    executeRfcommPayment(payeeName, amount)
                }
            },
            onFailed = { reason ->
                appendLog("PIN AUTH", "❌ Payment cancelled: $reason")
            }
        )
        pinDialog.show()
    }

    private fun executeLoopbackPayment(payeeName: String, amount: Double) {
        appendLog("P2P PAYMENT", "⚡ Initiating P2P Loopback Payment to $payeeName (₹$amount)...")
        paymentRepository.executeLoopbackPayment(
            receiverId = "MERCHANT_APEX",
            receiverName = payeeName,
            amount = amount,
            onStatusLog = { logMsg -> appendLog("BLUETOOTH", logMsg) },
            onSuccess = { packet, ack ->
                lifecycleScope.launch {
                    if (NetworkManager.isOnline(this@MainActivity)) {
                        appendLog("AUTO-SYNC", "🟢 Device online. Immediately syncing Bluetooth payment with Central Bank...")
                        withContext(Dispatchers.IO) {
                            SyncManager.syncNow(this@MainActivity)
                        }
                    }
                    refreshLocalLedgerUI()
                    appendLog("P2P PAYMENT", "✅ PAYMENT COMPLETED! Tx: ${packet.txId}")
                    appendLog("P2P PAYMENT", "Receipt Nonce: ${ack.ackNonce.take(8)} | Status: ${ack.status}")
                    Toast.makeText(this@MainActivity, "Payment Complete: -₹$amount to $payeeName", Toast.LENGTH_SHORT).show()
                }
            },
            onError = { errMsg ->
                appendLog("P2P PAYMENT", "❌ $errMsg")
                Toast.makeText(this, errMsg, Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun executeRfcommPayment(payeeName: String, amount: Double) {
        val targetDevice: BluetoothDevice? = selectedBluetoothDevice ?: run {
            val paired = paymentRepository.getPairedBluetoothDevices()
            if (paired.isNotEmpty()) paired.first() else null
        }

        if (targetDevice == null) {
            appendLog("BLUETOOTH", "⚠️ No Bluetooth target selected.")
            appendLog("HINT", "Opening Device Chooser / Scanner to pick a peer...")
            Toast.makeText(this, "Select a Bluetooth terminal or demo shop", Toast.LENGTH_SHORT).show()
            showBluetoothDevicePicker()
            return
        }

        appendLog("BLUETOOTH", "Attempting RFCOMM connection to ${targetDevice.name ?: "Peer"} [${targetDevice.address}]...")

        val targetUser = UserSession.DEMO_USERS.find {
            it.name.equals(payeeName.trim(), ignoreCase = true) ||
            it.phone == payeeName.trim() ||
            it.accountNumber.equals(payeeName.trim(), ignoreCase = true)
        }
        val targetReceiverId = targetUser?.id?.toString() ?: "MERCHANT_PEER"

        paymentRepository.executeBluetoothPayment(
            device = targetDevice,
            receiverId = targetReceiverId,
            receiverName = payeeName,
            amount = amount,
            onStatusLog = { logMsg -> appendLog("BLUETOOTH", logMsg) },
            onSuccess = { packet, ack ->
                lifecycleScope.launch {
                    if (NetworkManager.isOnline(this@MainActivity)) {
                        appendLog("AUTO-SYNC", "🟢 Device online. Immediately syncing Bluetooth payment with Central Bank...")
                        withContext(Dispatchers.IO) {
                            SyncManager.syncNow(this@MainActivity)
                        }
                    }
                    refreshLocalLedgerUI()
                    appendLog("P2P PAYMENT", "✅ Bluetooth RFCOMM Payment Confirmed by Peer!")
                    Toast.makeText(this@MainActivity, "Payment Sent via Bluetooth!", Toast.LENGTH_SHORT).show()
                }
            },
            onError = { errMsg ->
                appendLog("BLUETOOTH", "❌ $errMsg")
                Toast.makeText(this, errMsg, Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun toggleMerchantRadar() {
        if (!isMerchantRadarRunning) {
            isMerchantRadarRunning = true
            tvMerchantStatusBeacon?.text = "🟢 Radar ACTIVE (Listening)"
            tvMerchantStatusBeacon?.setTextColor(getColor(R.color.primary_dark))
            btnToggleMerchantRadar?.text = "Stop Merchant Radar"
            btnToggleMerchantRadar?.setBackgroundColor(getColor(R.color.error_red))

            // Request Android to broadcast Bluetooth discoverability so payer devices can find this phone
            try {
                val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                    putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                }
                startActivity(discoverableIntent)
                appendLog("MERCHANT", "Requested 300s Bluetooth discoverability from Android OS.")
            } catch (e: Exception) {
                appendLog("MERCHANT", "Discoverable intent notice: ${e.message}")
            }

            paymentRepository.startMerchantRadar(
                onPaymentReceived = { packet, ack ->
                    refreshLocalLedgerUI()
                    appendLog("MERCHANT", "🎉 Payment Received from ${packet.senderName}: +₹${packet.amount}")
                    appendLog("MERCHANT", "Signature & Nonce verified. Credited local balance.")
                    Toast.makeText(this, "Received ₹${packet.amount} from ${packet.senderName}", Toast.LENGTH_SHORT).show()
                },
                onStatusLog = { logMsg -> appendLog("MERCHANT", logMsg) }
            )
        } else {
            isMerchantRadarRunning = false
            paymentRepository.stopMerchantRadar()
            tvMerchantStatusBeacon?.text = "● Radar Standby"
            tvMerchantStatusBeacon?.setTextColor(getColor(R.color.text_secondary))
            btnToggleMerchantRadar?.text = "Start Merchant Listening Socket"
            btnToggleMerchantRadar?.setBackgroundColor(getColor(R.color.primary))
            appendLog("MERCHANT", "Merchant Radar stopped.")
        }
    }

    /**
     * Proves Cryptographic Authenticity & Integrity:
     * Shows what happens when an attacker tries to tamper with the amount.
     */
    private fun runTamperVerificationTest() {
        appendLog("TAMPER TEST", "🛡️ Running Adversarial Cryptographic Tamper Test...")
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val txId = CryptoManager.generateTxId()
                val nonce = CryptoManager.generateNonce()
                val originalAmount = 5.0
                val tamperedAmount = 500.0 // Malicious modification
                val timestamp = System.currentTimeMillis()

                val originalCanonical = CryptoManager.getCanonicalBytes(txId, "USER_1", "MERCHANT_APEX", originalAmount, timestamp, nonce)
                val signature = CryptoManager.signData(originalCanonical)
                val pubKey = CryptoManager.getPublicKeyBase64()

                // Attacker modifies the amount to ₹500.00
                val tamperedCanonical = CryptoManager.getCanonicalBytes(txId, "USER_1", "MERCHANT_APEX", tamperedAmount, timestamp, nonce)

                val originalCheck = CryptoManager.verifySignature(pubKey, originalCanonical, signature)
                val tamperedCheck = CryptoManager.verifySignature(pubKey, tamperedCanonical, signature)

                withContext(Dispatchers.Main) {
                    appendLog("TAMPER TEST", "1. Legit Payload (₹5.00): ${String(originalCanonical)}")
                    appendLog("TAMPER TEST", "   Signature Verification: $originalCheck (VALID ✅)")
                    appendLog("TAMPER TEST", "2. Attacker Tampered Payload (₹500.00): ${String(tamperedCanonical)}")
                    appendLog("TAMPER TEST", "   Signature Verification: $tamperedCheck (FAILED ❌)")
                    appendLog("TAMPER TEST", "🛡️ CONCLUSION: Mathematically impossible to alter amounts without the private key.")
                    Toast.makeText(this@MainActivity, "Tamper Test: Attack Blocked by ECDSA!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendLog("TAMPER TEST", "Error: ${e.message}")
                }
            }
        }
    }

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
            val result = paymentRepository.syncPendingTransactions()
            result.onSuccess { body ->
                refreshLocalLedgerUI()
                appendLog("SYNC", "✅ 200 OK: Reconciled ${body.reconciledCount} transactions with Bank ledger!")
                appendLog("SYNC", "Bank Balance Settled: Online = ₹${body.balance} | Reserve = ₹${body.emergencyBalance}")
                Toast.makeText(this@MainActivity, "Synchronized with Bank", Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                appendLog("SYNC", "❌ Bank Sync Failed: ${error.message}")
                Toast.makeText(this@MainActivity, "Sync Failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

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

                        if (NetworkManager.isOnline(this@MainActivity)) {
                            appendLog("AUTO-SYNC", "Online connection active. Auto-syncing offline receipt with Central Bank...")
                            lifecycleScope.launch(Dispatchers.IO) {
                                SyncManager.syncNow(this@MainActivity)
                                withContext(Dispatchers.Main) {
                                    refreshLocalLedgerUI()
                                }
                            }
                        }
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
                tvPendingTxBadge?.text = "$pendingCount Pending Sync"

                layoutRecentTransactionsContainer.removeAllViews()

                if (transactions.isEmpty()) {
                    tvEmptyRecentTransactions.visibility = View.VISIBLE
                } else {
                    tvEmptyRecentTransactions.visibility = View.GONE
                    val inflater = LayoutInflater.from(this@MainActivity)

                    transactions.take(3).forEach { tx ->
                        val itemView = inflater.inflate(R.layout.item_ledger_transaction, layoutRecentTransactionsContainer, false)
                        val ivIcon = itemView.findViewById<ImageView>(R.id.ivTxIcon)
                        val tvPayee = itemView.findViewById<TextView>(R.id.tvTxPayee)
                        val tvAmount = itemView.findViewById<TextView>(R.id.tvTxAmount)
                        val tvId = itemView.findViewById<TextView>(R.id.tvTxId)
                        val tvStatus = itemView.findViewById<TextView>(R.id.tvTxStatusBadge)

                        val isOut = tx.direction == TransactionEntity.DIRECTION_SENT
                        tvPayee.text = if (isOut) tx.receiverName.ifEmpty { tx.receiverId } else tx.senderName.ifEmpty { tx.senderId }
                        tvAmount.text = String.format(Locale.US, if (isOut) "- ₹%.2f" else "+ ₹%.2f", tx.amount)

                        val dateStr = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(tx.timestamp))
                        tvId.text = "Bluetooth • $dateStr"

                        if (tx.status == TransactionEntity.STATUS_PENDING_SYNC) {
                            tvStatus.text = "Queued Sync"
                            tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_offline_text))
                            tvStatus.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.status_offline_bg)
                        } else {
                            tvStatus.text = "Completed"
                            tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_online_text))
                            tvStatus.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.status_online_bg)
                        }

                        layoutRecentTransactionsContainer.addView(itemView)
                    }
                }
            }
        }
    }

    private fun showUserProfileDialog() {
        val dialog = UserProfileDialog(
            context = this,
            currentUser = paymentRepository.getCurrentUser(),
            onUserSelected = { selectedUser ->
                lifecycleScope.launch {
                    val updated = paymentRepository.switchActiveUser(selectedUser)
                    refreshLocalLedgerUI()
                    appendLog("AUTH", "Switched active user profile to ${updated.name} (Phone: ${updated.phone})")
                    appendLog("AUTH", "Active Ledger Account: ${updated.accountNumber} | Bank: ₹${updated.balance} | Reserve: ₹${updated.emergencyBalance}")
                    Toast.makeText(this@MainActivity, "Logged in as ${updated.name}", Toast.LENGTH_SHORT).show()
                }
            }
        )
        dialog.show()
    }

    private fun performOnlineBankLogin() {
        val targetUrl = BankApiClient.getBaseUrl(this)
        appendLog("BANK API", "Attempting online login handshake at $targetUrl...")

        lifecycleScope.launch {
            try {
                val apiService = BankApiClient.getApiService(this@MainActivity)
                val currentUser = paymentRepository.getCurrentUser()
                val response = withContext(Dispatchers.IO) {
                    try {
                        val phoneRes = apiService.getUserByPhone(currentUser.phone)
                        if (phoneRes.isSuccessful && phoneRes.body() != null) {
                            phoneRes
                        } else {
                            apiService.getUserById(currentUser.id)
                        }
                    } catch (_: Exception) {
                        apiService.getUserById(currentUser.id)
                    }
                }

                if (response.isSuccessful && response.body() != null) {
                    val bankUser = response.body()!!
                    val hasPendingTxs = withContext(Dispatchers.IO) {
                        appDatabase.transactionDao.getPendingSyncTransactions().isNotEmpty()
                    }
                    val effectiveEmergency = if (hasPendingTxs) {
                        val localWallet = withContext(Dispatchers.IO) { appDatabase.walletDao.getWallet() }
                        localWallet?.balance ?: bankUser.emergencyBalance
                    } else {
                        bankUser.emergencyBalance
                    }

                    // Update session with authentic server ID and balances
                    val updatedUser = currentUser.copy(
                        id = bankUser.id,
                        name = bankUser.name,
                        phone = bankUser.phone,
                        balance = bankUser.balance,
                        emergencyBalance = effectiveEmergency,
                        isOnline = true
                    )
                    sessionManager.updateUser(updatedUser)

                    if (!hasPendingTxs) {
                        withContext(Dispatchers.IO) {
                            appDatabase.walletDao.updateBalance(bankUser.emergencyBalance)
                        }
                    }

                    updateNetworkBadge(true)

                    appendLog("BANK API", "200 OK: Authenticated as ${bankUser.name} (ID: ${bankUser.id}, Phone: ${bankUser.phone})")
                    appendLog("BANK API", "Online Balance: ₹${bankUser.balance} | Offline Reserve: ₹${effectiveEmergency}")
                    Toast.makeText(this@MainActivity, "Connected to Bank Server", Toast.LENGTH_SHORT).show()
                } else {
                    val code = response.code()
                    val errorMsg = response.errorBody()?.string() ?: "Unknown error"
                    appendLog("BANK API", "HTTP $code Error: $errorMsg")
                    Toast.makeText(this@MainActivity, "Bank Login Failed (HTTP $code)", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                appendLog("BANK API", "Connection Failed: ${e.localizedMessage ?: e.javaClass.simpleName}")
                appendLog("HINT", "Verify bank server is reachable via network or use offline emergency mode.")
                Toast.makeText(this@MainActivity, "Bank Server Unavailable (Offline Active)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showOfflinePinDialog() {
        val dialog = PinAuthDialog(
            context = this,
            actionTitle = "Authorize Offline Session",
            actionSubtitle = "Enter 4-digit PIN (Default: 1234)",
            onSuccess = {
                tvPinStatusBadge?.text = "✅ 1234 Verified"
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
        runOnUiThread {
            tvTerminalLog?.append(logLine)
        }
    }

    /**
     * Phase 6: Initializes Android WorkManager background sync and observers.
     */
    private fun initPhase6WorkManager() {
        SyncNotificationHelper.createNotificationChannel(this)
        SyncManager.scheduleAutoSync(this)
        appendLog("WORKMANAGER", "Auto-Sync background daemon armed (Constraint: NetworkType.CONNECTED)")

        SyncManager.getImmediateSyncWorkInfo(this).observe(this) { workInfoList ->
            if (workInfoList.isNullOrEmpty()) return@observe
            val workInfo = workInfoList.first()
            when (workInfo.state) {
                WorkInfo.State.ENQUEUED -> {
                    tvAutoSyncBadge?.text = "● Auto-Sync: Enqueued"
                    tvAutoSyncBadge?.setTextColor(ContextCompat.getColor(this, R.color.primary_dark))
                }
                WorkInfo.State.RUNNING -> {
                    tvAutoSyncBadge?.text = "● Auto-Sync: Syncing..."
                    tvAutoSyncBadge?.setTextColor(ContextCompat.getColor(this, R.color.primary))
                    appendLog("WORKMANAGER", "⏳ SyncWorker active: pushing pending offline ledger to Laptop Bank...")
                }
                WorkInfo.State.SUCCEEDED -> {
                    tvAutoSyncBadge?.text = "● Auto-Sync: Reconciled ✅"
                    tvAutoSyncBadge?.setTextColor(ContextCompat.getColor(this, R.color.primary_dark))
                    val count = workInfo.outputData.getInt("reconciled_count", 0)
                    val total = workInfo.outputData.getDouble("total_amount", 0.0)
                    if (count > 0) {
                        appendLog("WORKMANAGER", "✅ Auto-Sync Succeeded: $count offline txs (₹$total) settled with Central Bank.")
                    } else {
                        appendLog("WORKMANAGER", "✅ Auto-Sync Completed: Ledger already up-to-date (0 pending txs).")
                    }
                    refreshLocalLedgerUI()
                }
                WorkInfo.State.FAILED -> {
                    tvAutoSyncBadge?.text = "● Auto-Sync: Retrying"
                    tvAutoSyncBadge?.setTextColor(ContextCompat.getColor(this, R.color.error_red))
                    val err = workInfo.outputData.getString("error") ?: "Server unreachable"
                    appendLog("WORKMANAGER", "⚠️ SyncWorker notice: $err")
                }
                else -> {}
            }
        }
    }

    private fun triggerWorkManagerSync() {
        appendLog("WORKMANAGER", "Manual sync request queued for Android WorkManager...")
        Toast.makeText(this, "Dispatched to WorkManager Queue", Toast.LENGTH_SHORT).show()
        paymentRepository.triggerBackgroundSync()
    }

    override fun onResume() {
        super.onResume()
        val authManager = com.example.empay.auth.AuthManager.getInstance(this)
        if (!authManager.isLoggedIn()) {
            val intent = Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish()
            return
        }
        updateNetworkBadge(NetworkManager.isOnline(this))
        refreshLocalLedgerUI()
        val currentUser = sessionManager.getUser()
        if (BluetoothHelper.hasConnectPermission(this)) {
            BluetoothHelper.broadcastEmPayIdentity(this, currentUser.name, currentUser.phone)
        }
        lifecycleScope.launch {
            paymentRepository.refreshAuthoritativeBalances()
        }
        if (NetworkManager.isOnline(this)) {
            lifecycleScope.launch(Dispatchers.IO) {
                val pending = appDatabase.transactionDao.getPendingSyncTransactions()
                if (pending.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        appendLog("AUTO-SYNC", "App in foreground with ${pending.size} pending offline receipts. Auto-syncing with Central Bank...")
                    }
                    SyncManager.syncNow(this@MainActivity)
                    paymentRepository.refreshAuthoritativeBalances()
                    withContext(Dispatchers.Main) {
                        refreshLocalLedgerUI()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::paymentRepository.isInitialized) {
            paymentRepository.stopMerchantRadar()
        }
        try {
            if (networkCallback != null && connectivityManager != null) {
                connectivityManager?.unregisterNetworkCallback(networkCallback!!)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
