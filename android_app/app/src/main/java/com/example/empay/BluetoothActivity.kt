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
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
        val rawDevice: BluetoothDevice? = null
    )

    private val merchantsList = mutableListOf(
        PeerItem(1, "Apex City Care Pharmacy", "Healthcare", 1.2, true),
        PeerItem(2, "Daily Fresh Supermarket", "Groceries", 0.8, true),
        PeerItem(3, "LifeLine Emergency Medicos", "Healthcare", 1.5, true),
        PeerItem(4, "National Book & Stationery Depot", "Retail", 2.4, true),
        PeerItem(5, "City QuickFix Electrical Services", "Emergency Services", 1.8, true)
    )

    private val usersList = mutableListOf<PeerItem>()

    private var isReceiverRegistered = false
    private val btDiscoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (BluetoothDevice.ACTION_FOUND == action || BluetoothDevice.ACTION_NAME_CHANGED == action) {
                val device: BluetoothDevice? =
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                val extraName: String? = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
                device?.let { handleDiscoveredDevice(it, extraName) }
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
        val currentUser = sessionManager.getUser()
        BluetoothHelper.broadcastEmPayIdentity(this, currentUser.name, currentUser.phone)

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter != null && !adapter.isEnabled) {
            try {
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (_: Exception) {}
        }

        // Request Bluetooth Discoverability once so nearby peer devices running EmPay can discover this device
        if (!hasRequestedDiscoverable) {
            hasRequestedDiscoverable = true
            try {
                if (adapter != null && adapter.isEnabled && adapter.scanMode != BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
                    val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                        putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                    }
                    startActivity(discoverableIntent)
                }
            } catch (_: Exception) {}
        }

        loadBalances()
        lifecycleScope.launch {
            paymentRepository.refreshAuthoritativeBalances()
            loadBalances()
        }

        // Pull latest registered users dynamically from bank server so any new user (Krrish, Rabel, etc.) appears immediately
        AuthManager.getInstance(this).syncAllRegisteredUsersFromBackend {
            populateKnownUsers()
            scanPairedAndNearbyDevices()
            renderList()
        }

        populateKnownUsers()
        scanPairedAndNearbyDevices()
        startActiveDiscovery()
        renderList()
    }

    override fun onPause() {
        super.onPause()
        stopActiveDiscovery()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopActiveDiscovery()
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

    private fun populateKnownUsers() {
        val currentPhone = sessionManager.getUser().phone
        val currentName = sessionManager.getUser().name
        val registeredAccounts = AuthManager.getInstance(this).getRegisteredAccounts()

        registeredAccounts.forEach { account ->
            if (account.phone != currentPhone && !account.name.equals(currentName, true)) {
                val exists = usersList.any { it.categoryOrPhone == account.phone || it.name.equals(account.name, true) }
                if (!exists) {
                    usersList.add(
                        PeerItem(
                            id = usersList.size + 1L,
                            name = account.name,
                            categoryOrPhone = account.phone,
                            distanceMeters = 1.0 + (usersList.size * 0.5),
                            isMerchant = false
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
                        merchantsList[mIdx] = merchantsList[mIdx].copy(rawDevice = dev)
                    } else {
                        merchantsList.add(
                            0,
                            PeerItem(
                                id = 1000L + merchantsList.size,
                                name = peer.name,
                                categoryOrPhone = "Emergency Merchant",
                                distanceMeters = 0.5,
                                isMerchant = true,
                                rawDevice = dev
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
                            distanceMeters = 0.5
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
                                rawDevice = dev
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
            }
            if (!isReceiverRegistered) {
                registerReceiver(btDiscoveryReceiver, filter)
                isReceiverRegistered = true
            }
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

    private fun handleDiscoveredDevice(device: BluetoothDevice, extraName: String? = null) {
        val peer = BluetoothHelper.parseEmPayPeer(this, device, extraName) ?: return
        val currentPhone = sessionManager.getUser().phone
        val currentName = sessionManager.getUser().name
        if (peer.phone == currentPhone || peer.name.equals(currentName, true)) return

        var updated = false
        if (peer.isMerchant) {
            val mIdx = merchantsList.indexOfFirst { it.name.equals(peer.name, true) }
            if (mIdx >= 0) {
                merchantsList[mIdx] = merchantsList[mIdx].copy(rawDevice = device)
                updated = true
            } else {
                merchantsList.add(
                    0,
                    PeerItem(
                        id = 3000L + merchantsList.size,
                        name = peer.name,
                        categoryOrPhone = "Emergency Merchant",
                        distanceMeters = 0.5,
                        isMerchant = true,
                        rawDevice = device
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
                    distanceMeters = 0.5
                )
                updated = true
            } else {
                usersList.add(
                    0,
                    PeerItem(
                        id = 4000L + usersList.size,
                        name = peer.name,
                        categoryOrPhone = peer.phone,
                        distanceMeters = 0.5,
                        isMerchant = false,
                        rawDevice = device
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
                tvSub.text = "${item.categoryOrPhone} • ${String.format(Locale.US, "%.1fm", item.distanceMeters)}$deviceStatus"
            } else {
                ivIcon.setImageResource(R.drawable.ic_profile)
                val phoneDisp = if (item.categoryOrPhone.startsWith("+")) item.categoryOrPhone else "+91 ${item.categoryOrPhone}"
                val deviceStatus = if (item.rawDevice != null) " • Bluetooth Nearby" else ""
                tvSub.text = "$phoneDisp • ${String.format(Locale.US, "%.1fm", item.distanceMeters)}$deviceStatus"
            }

            val payAction = View.OnClickListener {
                val intent = Intent(this, PayActivity::class.java).apply {
                    putExtra("PAYEE_NAME", item.name)
                    if (!item.isMerchant) {
                        putExtra("PHONE", item.categoryOrPhone)
                    }
                    if (item.rawDevice != null) {
                        putExtra("DEVICE_ADDRESS", item.rawDevice.address)
                    }
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
