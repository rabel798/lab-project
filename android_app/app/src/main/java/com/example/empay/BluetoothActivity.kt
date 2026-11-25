package com.example.empay

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.empay.auth.AuthManager
import com.example.empay.auth.SessionManager
import com.example.empay.bluetooth.BluetoothHelper
import com.example.empay.bluetooth.BluetoothPaymentEngine
import com.example.empay.db.AppDatabase
import com.example.empay.repository.PaymentRepository
import kotlinx.coroutines.launch
import java.util.Locale

class BluetoothActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager
    private lateinit var appDatabase: AppDatabase
    private lateinit var paymentRepository: PaymentRepository
    private lateinit var bluetoothEngine: BluetoothPaymentEngine

    private lateinit var tvBtEmergencyBalance: TextView
    private lateinit var etBtSearch: EditText
    private lateinit var tvTabBtMerchantsText: TextView
    private lateinit var indicatorBtMerchants: View
    private lateinit var tvTabBtUsersText: TextView
    private lateinit var indicatorBtUsers: View
    private lateinit var layoutBtDevicesList: LinearLayout
    private lateinit var btnBtRefresh: ImageButton
    private lateinit var tvBtScanStatus: TextView

    private var activeTab = "users" // Default to users tab so nearby registered accounts are immediately visible
    private var searchQuery = ""
    private var hasRequestedDiscoverable = false
    private val REQUEST_BT_PERMISSIONS = 2001

    data class PeerItem(
        val id: Long,
        val name: String,
        val categoryOrPhone: String,
        val distanceMeters: Double,
        val isMerchant: Boolean,
        val rawDevice: BluetoothDevice? = null,
        val lastSeenTimestamp: Long = System.currentTimeMillis(),
        val isHardcoded: Boolean = false
    )

    private val merchantsList = mutableListOf(
        PeerItem(1, "Apex City Care Pharmacy", "Healthcare", 1.2, true, isHardcoded = true),
        PeerItem(2, "Daily Fresh Supermarket", "Groceries", 0.8, true, isHardcoded = true),
        PeerItem(3, "LifeLine Emergency Medicos", "Healthcare", 1.5, true, isHardcoded = true),
        PeerItem(4, "National Book & Stationery Depot", "Retail", 2.4, true, isHardcoded = true),
        PeerItem(5, "City QuickFix Electrical Services", "Emergency Services", 1.8, true, isHardcoded = true)
    )

    private val usersList = mutableListOf<PeerItem>()

    private var isActivityActive = false
    private val PEER_TTL_MS = 2500L // 2.5s TTL: device disappears as soon as it turns off BT or moves away
    private val pruneHandler = Handler(Looper.getMainLooper())
    private val pruneRunnable = object : Runnable {
        override fun run() {
            if (isActivityActive) {
                pruneOutOfRangePeers()
                pruneHandler.postDelayed(this, 1000L) // Scan and purge stale signals every 1 second
            }
        }
    }
    private val restartDiscoveryHandler = Handler(Looper.getMainLooper())

    private var isReceiverRegistered = false
    private val btDiscoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                BluetoothDevice.ACTION_FOUND, BluetoothDevice.ACTION_NAME_CHANGED -> {
                    val device: BluetoothDevice? =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val extraName: String? = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
                    val rssi: Short = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                    val rssiVal: Int? = if (rssi != Short.MIN_VALUE) rssi.toInt() else null
                    device?.let { handleDiscoveredDevice(it, extraName, rssiVal) }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device: BluetoothDevice? =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let { handleDeviceDisconnected(it) }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    pruneOutOfRangePeers()
                    val count = (if (activeTab == "merchants") merchantsList else usersList).size
                    tvBtScanStatus.text = if (count > 0) "$count Devices in Vicinity" else "Nearby Devices Detected"
                    if (isActivityActive) {
                        restartDiscoveryHandler.removeCallbacksAndMessages(null)
                        restartDiscoveryHandler.postDelayed({
                            if (isActivityActive) {
                                triggerDiscovery()
                            }
                        }, 2000L)
                    }
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                        usersList.removeAll { !it.isHardcoded }
                        merchantsList.removeAll { !it.isHardcoded }
                        renderList()
                        bluetoothEngine.stopMerchantServer()
                        tvBtScanStatus.text = "Bluetooth is Turned Off"
                    } else if (state == BluetoothAdapter.STATE_ON) {
                        val currentUser = sessionManager.getUser()
                        BluetoothHelper.broadcastEmPayIdentity(this@BluetoothActivity, currentUser.name, currentUser.phone)
                        ensureDiscoverableAndStartServer()
                        triggerDiscovery()
                        tvBtScanStatus.text = "Scanning for Nearby EmPay Devices..."
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bluetooth)

        sessionManager = SessionManager.getInstance(this)
        appDatabase = AppDatabase.getInstance(this)
        paymentRepository = PaymentRepository.getInstance(this)
        bluetoothEngine = BluetoothPaymentEngine(this)

        val currentUser = sessionManager.getUser()
        BluetoothHelper.broadcastEmPayIdentity(this, currentUser.name, currentUser.phone)

        initViews()
        setupListeners()
        updateTabsUi()
        loadBalances()
        populateKnownUsers()
        scanPairedAndNearbyDevices()
        renderList()
        checkBluetoothPermissions()
    }

    private fun checkBluetoothPermissions(): Boolean {
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
            ActivityCompat.requestPermissions(this, required.toTypedArray(), REQUEST_BT_PERMISSIONS)
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BT_PERMISSIONS) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                val currentUser = sessionManager.getUser()
                BluetoothHelper.broadcastEmPayIdentity(this, currentUser.name, currentUser.phone)
                scanPairedAndNearbyDevices()
                startActiveDiscovery()
                renderList()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isActivityActive = true
        pruneHandler.removeCallbacks(pruneRunnable)
        pruneHandler.postDelayed(pruneRunnable, 3000L)

        val currentUser = sessionManager.getUser()
        BluetoothHelper.broadcastEmPayIdentity(this, currentUser.name, currentUser.phone)

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter != null && !adapter.isEnabled) {
            try {
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (_: Exception) {}
        }

        ensureDiscoverableAndStartServer()

        loadBalances()
        lifecycleScope.launch {
            paymentRepository.refreshAuthoritativeBalances()
            loadBalances()
        }

        // Sync bank database in background for account lookup/verification,
        // without dumping unverified remote users into the nearby Bluetooth list
        AuthManager.getInstance(this).syncAllRegisteredUsersFromBackend()

        populateKnownUsers()
        scanPairedAndNearbyDevices()
        startActiveDiscovery()
        renderList()
    }

    override fun onPause() {
        super.onPause()
        isActivityActive = false
        pruneHandler.removeCallbacks(pruneRunnable)
        stopActiveDiscovery()
        bluetoothEngine.stopMerchantServer()
    }

    override fun onDestroy() {
        super.onDestroy()
        isActivityActive = false
        pruneHandler.removeCallbacks(pruneRunnable)
        stopActiveDiscovery()
        bluetoothEngine.stopMerchantServer()
    }

    private fun ensureDiscoverableAndStartServer() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        if (!adapter.isEnabled) return

        try {
            if (adapter.scanMode != BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
                val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                    putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                }
                startActivity(discoverableIntent)
            }
        } catch (_: Exception) {}

        startPaymentServerListener()
    }

    private fun startPaymentServerListener() {
        try {
            bluetoothEngine.startMerchantServer(
                onPaymentReceived = { packet, ack ->
                    runOnUiThread {
                        loadBalances()
                        renderList()
                        Toast.makeText(
                            this@BluetoothActivity,
                            "⚡ Received ₹${String.format(Locale.US, "%.2f", packet.amount)} from ${packet.senderName} via Bluetooth!",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                },
                onStatusLog = { log ->
                    android.util.Log.d("EmPayBT", log)
                },
                onError = { err ->
                    android.util.Log.e("EmPayBT", err)
                }
            )
        } catch (_: Exception) {}
    }

    private fun triggerRefreshRadar() {
        val rotate = android.view.animation.RotateAnimation(
            0f, 360f,
            android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
            android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 800
            repeatCount = 1
        }
        btnBtRefresh.startAnimation(rotate)
        tvBtScanStatus.text = "Scanning for Nearby EmPay Devices..."

        val currentUser = sessionManager.getUser()
        BluetoothHelper.broadcastEmPayIdentity(this, currentUser.name, currentUser.phone)
        ensureDiscoverableAndStartServer()
        triggerDiscovery()

        AuthManager.getInstance(this).syncAllRegisteredUsersFromBackend {
            runOnUiThread {
                populateKnownUsers()
                renderList()
            }
        }
        Toast.makeText(this, "Refreshing Bluetooth mesh radar...", Toast.LENGTH_SHORT).show()
    }

    private fun initViews() {
        findViewById<ImageButton>(R.id.btnBtBack).setOnClickListener { finish() }

        tvBtEmergencyBalance = findViewById(R.id.tvBtEmergencyBalance)
        etBtSearch = findViewById(R.id.etBtSearch)
        tvTabBtMerchantsText = findViewById(R.id.tvTabBtMerchantsText)
        indicatorBtMerchants = findViewById(R.id.indicatorBtMerchants)
        tvTabBtUsersText = findViewById(R.id.tvTabBtUsersText)
        indicatorBtUsers = findViewById(R.id.indicatorBtUsers)
        layoutBtDevicesList = findViewById(R.id.layoutBtDevicesList)
        btnBtRefresh = findViewById(R.id.btnBtRefresh)
        tvBtScanStatus = findViewById(R.id.tvBtScanStatus)

        // Bottom nav
        findViewById<View>(R.id.navBtHome).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }
        findViewById<View>(R.id.navBtScan).setOnClickListener {
            startActivity(Intent(this, ScanActivity::class.java))
            finish()
        }
        findViewById<View>(R.id.navBtHistory).setOnClickListener {
            startActivity(Intent(this, TransactionHistoryActivity::class.java))
            finish()
        }
        findViewById<View>(R.id.navBtProfile).setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
            finish()
        }
    }

    private fun setupListeners() {
        btnBtRefresh.setOnClickListener {
            triggerRefreshRadar()
        }

        findViewById<View>(R.id.tabBtMerchants).setOnClickListener {
            activeTab = "merchants"
            updateTabsUi()
            renderList()
        }

        findViewById<View>(R.id.tabBtUsers).setOnClickListener {
            activeTab = "users"
            updateTabsUi()
            renderList()
        }

        etBtSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.trim()?.lowercase(Locale.US) ?: ""
                renderList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun updateTabsUi() {
        val primaryColor = ContextCompat.getColor(this, R.color.primary)
        val mutedColor = ContextCompat.getColor(this, R.color.text_muted)

        if (activeTab == "merchants") {
            tvTabBtMerchantsText.setTextColor(primaryColor)
            tvTabBtMerchantsText.paint.isFakeBoldText = true
            indicatorBtMerchants.setBackgroundColor(primaryColor)

            tvTabBtUsersText.setTextColor(mutedColor)
            tvTabBtUsersText.paint.isFakeBoldText = false
            indicatorBtUsers.setBackgroundColor(Color.TRANSPARENT)
        } else {
            tvTabBtUsersText.setTextColor(primaryColor)
            tvTabBtUsersText.paint.isFakeBoldText = true
            indicatorBtUsers.setBackgroundColor(primaryColor)

            tvTabBtMerchantsText.setTextColor(mutedColor)
            tvTabBtMerchantsText.paint.isFakeBoldText = false
            indicatorBtMerchants.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    private fun loadBalances() {
        val user = sessionManager.getUser()
        val wallet = appDatabase.walletDao.getWallet()
        val offlineBal = wallet?.balance ?: user.emergencyBalance
        tvBtEmergencyBalance.text = String.format(Locale.US, "₹ %.2f", offlineBal)
    }

    /**
     * Calculates estimated physical distance in meters from Bluetooth Received Signal Strength Indication (RSSI).
     * Log-distance path loss model:
     * Distance = 10 ^ ((TxPower - RSSI) / (10 * n))
     * where TxPower = -59 dBm (measured RSSI at 1m), n = 2.2 (indoor path loss exponent)
     */
    private fun calculateDistanceMeters(rssi: Int?): Double {
        if (rssi == null || rssi == 0) return 0.8
        val txPower = -59.0
        val pathLossExponent = 2.2
        val ratio = (txPower - rssi) / (10.0 * pathLossExponent)
        val rawDistance = Math.pow(10.0, ratio).coerceIn(0.2, 25.0)
        return Math.round(rawDistance * 10.0) / 10.0
    }

    private fun populateKnownUsers() {
        val currentPhone = sessionManager.getUser().phone
        val currentName = sessionManager.getUser().name

        // Pre-populate only the designated hardcoded demo testers
        val hardcodedTesters = listOf(
            Triple("Priya Sharma", "8765432109", 1.2),
            Triple("Sukhpal Singh", "9811122233", 1.8),
            Triple("Dr. Rohan Verma", "7654321098", 2.4),
            Triple("Ananya Patel", "6543210987", 3.1)
        )

        hardcodedTesters.forEach { (name, phone, dist) ->
            if (phone != currentPhone && !name.equals(currentName, true)) {
                if (usersList.none { it.categoryOrPhone == phone || it.name.equals(name, true) }) {
                    usersList.add(
                        PeerItem(
                            id = usersList.size + 1L,
                            name = name,
                            categoryOrPhone = phone,
                            distanceMeters = dist,
                            isMerchant = false,
                            isHardcoded = true
                        )
                    )
                }
            }
        }
    }

    private fun scanPairedAndNearbyDevices() {
        try {
            val paired = paymentRepository.getPairedBluetoothDevices()
            val currentPhone = sessionManager.getUser().phone
            val currentName = sessionManager.getUser().name

            for (dev in paired) {
                val peer = BluetoothHelper.parseEmPayPeer(this, dev) ?: continue
                if (peer.phone == currentPhone || peer.name.equals(currentName, true)) continue // Skip self

                if (peer.isMerchant) {
                    val mIdx = merchantsList.indexOfFirst { it.name.equals(peer.name, true) }
                    if (mIdx >= 0) {
                        merchantsList[mIdx] = merchantsList[mIdx].copy(rawDevice = dev, lastSeenTimestamp = System.currentTimeMillis())
                    } else {
                        merchantsList.add(
                            0,
                            PeerItem(
                                id = 1000L + merchantsList.size,
                                name = peer.name,
                                categoryOrPhone = "Emergency Merchant",
                                distanceMeters = 0.5,
                                isMerchant = true,
                                rawDevice = dev,
                                lastSeenTimestamp = System.currentTimeMillis(),
                                isHardcoded = false
                            )
                        )
                    }
                } else {
                    val uIdx = usersList.indexOfFirst { 
                        (peer.phone.isNotEmpty() && it.categoryOrPhone == peer.phone) ||
                        it.name.equals(peer.name, true)
                    }
                    if (uIdx >= 0) {
                        usersList[uIdx] = usersList[uIdx].copy(
                            name = peer.name,
                            categoryOrPhone = peer.phone.ifEmpty { usersList[uIdx].categoryOrPhone },
                            rawDevice = dev,
                            distanceMeters = 0.5,
                            lastSeenTimestamp = System.currentTimeMillis()
                        )
                    } else {
                        usersList.add(
                            0,
                            PeerItem(
                                id = 2000L + usersList.size,
                                name = peer.name,
                                categoryOrPhone = peer.phone,
                                distanceMeters = 0.8,
                                isMerchant = false,
                                rawDevice = dev,
                                lastSeenTimestamp = System.currentTimeMillis(),
                                isHardcoded = false
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startActiveDiscovery() {
        if (!BluetoothHelper.hasScanPermission(this)) return
        try {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothDevice.ACTION_NAME_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            }
            if (!isReceiverRegistered) {
                registerReceiver(btDiscoveryReceiver, filter)
                isReceiverRegistered = true
            }
            triggerDiscovery()
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun triggerDiscovery() {
        if (!BluetoothHelper.hasScanPermission(this)) return
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter?.isEnabled == true) {
                if (adapter.isDiscovering) {
                    adapter.cancelDiscovery()
                }
                adapter.startDiscovery()
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun stopActiveDiscovery() {
        restartDiscoveryHandler.removeCallbacksAndMessages(null)
        try {
            if (isReceiverRegistered) {
                unregisterReceiver(btDiscoveryReceiver)
                isReceiverRegistered = false
            }
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter != null && BluetoothHelper.hasScanPermission(this) && adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }
        } catch (_: Exception) {}
    }

    private fun handleDeviceDisconnected(device: BluetoothDevice) {
        val addr = device.address
        val removedUser = usersList.removeAll { !it.isHardcoded && it.rawDevice?.address == addr }
        val removedMerchant = merchantsList.removeAll { !it.isHardcoded && it.rawDevice?.address == addr }
        if (removedUser || removedMerchant) {
            runOnUiThread { renderList() }
        }
    }

    private fun pruneOutOfRangePeers() {
        val now = System.currentTimeMillis()
        val removedUsers = usersList.removeAll { !it.isHardcoded && (now - it.lastSeenTimestamp > PEER_TTL_MS) }
        val removedMerchants = merchantsList.removeAll { !it.isHardcoded && (now - it.lastSeenTimestamp > PEER_TTL_MS) }
        if (removedUsers || removedMerchants) {
            runOnUiThread { renderList() }
        }
    }

    private fun handleDiscoveredDevice(device: BluetoothDevice, extraName: String? = null, rssi: Int? = null) {
        val peer = BluetoothHelper.parseEmPayPeer(this, device, extraName) ?: return
        val currentPhone = sessionManager.getUser().phone
        val currentName = sessionManager.getUser().name
        if (peer.phone == currentPhone || peer.name.equals(currentName, true)) return

        val measuredDistance = calculateDistanceMeters(rssi)
        val now = System.currentTimeMillis()
        var updated = false
        if (peer.isMerchant) {
            val mIdx = merchantsList.indexOfFirst { it.name.equals(peer.name, true) }
            if (mIdx >= 0) {
                merchantsList[mIdx] = merchantsList[mIdx].copy(
                    rawDevice = device,
                    distanceMeters = measuredDistance,
                    lastSeenTimestamp = now
                )
                updated = true
            } else {
                merchantsList.add(
                    0,
                    PeerItem(
                        id = 3000L + merchantsList.size,
                        name = peer.name,
                        categoryOrPhone = "Emergency Merchant",
                        distanceMeters = measuredDistance,
                        isMerchant = true,
                        rawDevice = device,
                        lastSeenTimestamp = now,
                        isHardcoded = false
                    )
                )
                updated = true
            }
        } else {
            val uIdx = usersList.indexOfFirst { 
                (peer.phone.isNotEmpty() && it.categoryOrPhone == peer.phone) ||
                it.name.equals(peer.name, true)
            }
            if (uIdx >= 0) {
                usersList[uIdx] = usersList[uIdx].copy(
                    name = peer.name,
                    categoryOrPhone = peer.phone.ifEmpty { usersList[uIdx].categoryOrPhone },
                    rawDevice = device,
                    distanceMeters = measuredDistance,
                    lastSeenTimestamp = now
                )
                updated = true
            } else {
                usersList.add(
                    0,
                    PeerItem(
                        id = 4000L + usersList.size,
                        name = peer.name,
                        categoryOrPhone = peer.phone,
                        distanceMeters = measuredDistance,
                        isMerchant = false,
                        rawDevice = device,
                        lastSeenTimestamp = now,
                        isHardcoded = false
                    )
                )
                updated = true
            }
        }

        if (updated) {
            runOnUiThread { renderList() }
        }
    }

    private fun renderList() {
        layoutBtDevicesList.removeAllViews()

        val list = if (activeTab == "merchants") merchantsList else usersList
        val filtered = list.filter {
            it.name.lowercase(Locale.US).contains(searchQuery) ||
            it.categoryOrPhone.lowercase(Locale.US).contains(searchQuery)
        }

        if (filtered.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = if (activeTab == "merchants") "No EmPay Merchants found nearby" else "No EmPay Users found nearby"
                textSize = 14f
                setTextColor(Color.parseColor("#6B7280"))
                gravity = android.view.Gravity.CENTER
                setPadding(0, 48, 0, 48)
            }
            layoutBtDevicesList.addView(emptyTv)
            return
        }

        val inflater = LayoutInflater.from(this)
        for (item in filtered) {
            val itemView = inflater.inflate(R.layout.item_bluetooth_device, layoutBtDevicesList, false)
            
            val ivIcon = itemView.findViewById<ImageView>(R.id.ivItemDeviceIcon)
            val tvName = itemView.findViewById<TextView>(R.id.tvDeviceName)
            val tvSub = itemView.findViewById<TextView>(R.id.tvDeviceAddress)
            val btnPay = itemView.findViewById<TextView>(R.id.tvDeviceBadge)

            tvName.text = item.name
            if (item.isMerchant) {
                ivIcon.setImageResource(R.drawable.ic_store)
                val deviceStatus = if (item.rawDevice != null) " • Bluetooth Paired" else ""
                tvSub.text = "${item.categoryOrPhone} • ${String.format(Locale.US, "%.1fm away", item.distanceMeters)}$deviceStatus"
            } else {
                ivIcon.setImageResource(R.drawable.ic_profile)
                val phoneDisp = if (item.categoryOrPhone.startsWith("+")) item.categoryOrPhone else "+91 ${item.categoryOrPhone}"
                val deviceStatus = if (item.rawDevice != null) " • Bluetooth In Vicinity" else ""
                tvSub.text = "$phoneDisp • ${String.format(Locale.US, "%.1fm away", item.distanceMeters)}$deviceStatus"
            }

            val payAction = View.OnClickListener {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                if (adapter == null || !adapter.isEnabled) {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("⚠️ Bluetooth Not Detected")
                        .setMessage("Your Bluetooth is turned off. Please turn on Bluetooth to connect and pay peers.")
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setPositiveButton("OK", null)
                        .show()
                    return@OnClickListener
                }

                // If dynamic peer, verify signal was heard within the last 2.5 seconds
                if (!item.isHardcoded) {
                    val age = System.currentTimeMillis() - item.lastSeenTimestamp
                    if (age > 2500L) {
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("⚠️ Bluetooth Not Detected")
                            .setMessage("Bluetooth signal lost: '${item.name}' is no longer detected. Their Bluetooth was turned off or they moved out of range.")
                            .setIcon(android.R.drawable.ic_dialog_alert)
                            .setPositiveButton("OK", null)
                            .show()
                        usersList.removeAll { it.id == item.id }
                        merchantsList.removeAll { it.id == item.id }
                        renderList()
                        return@OnClickListener
                    }
                }

                val intent = Intent(this, PayActivity::class.java).apply {
                    putExtra("PAYEE_NAME", item.name)
                    if (!item.isMerchant) {
                        putExtra("PHONE", item.categoryOrPhone)
                    }
                    if (item.rawDevice != null) {
                        putExtra("DEVICE_ADDRESS", item.rawDevice.address)
                    }
                    putExtra("LAST_SEEN_MS", item.lastSeenTimestamp)
                    putExtra("CHANNEL", "Bluetooth")
                    putExtra("IS_OFFLINE", true)
                }
                startActivity(intent)
            }

            itemView.setOnClickListener(payAction)
            btnPay.setOnClickListener(payAction)

            layoutBtDevicesList.addView(itemView)
        }
    }
}
