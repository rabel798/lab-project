package com.example.empay.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.empay.R
import com.example.empay.bluetooth.BluetoothHelper
import com.example.empay.bluetooth.BluetoothPaymentEngine

/**
 * Interactive Bluetooth Discovery & Selection Dialog.
 * Shows paired hardware terminals, actively scans for nearby Bluetooth devices,
 * and lists essential disaster-relief merchant demo terminals.
 */
class BluetoothDeviceDialog(
    private val context: Context,
    private val bluetoothEngine: BluetoothPaymentEngine,
    private val onRequestPermissions: (() -> Unit)? = null,
    private val onDeviceSelected: (String, BluetoothDevice?, Boolean) -> Unit
) {

    private val dialog: Dialog = Dialog(context)
    private val discoveredDevicesSet = mutableSetOf<String>()
    private var isReceiverRegistered = false

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    if (device != null) {
                        val peer = BluetoothHelper.parseEmPayPeer(context, device)
                        if (peer != null) {
                            val address = device.address ?: ""
                            if (!discoveredDevicesSet.contains(address)) {
                                discoveredDevicesSet.add(address)
                                val displayName = peer.name
                                val displaySub = if (peer.phone.isNotEmpty()) "${peer.phone} • Nearby EmPay User" else "$address • EmPay Device"
                                addDeviceItem(
                                    container = containerDiscovered,
                                    name = displayName,
                                    address = displaySub,
                                    badgeText = "Pay Offline",
                                    onClick = {
                                        cleanup()
                                        dialog.dismiss()
                                        onDeviceSelected(displayName, device, false)
                                    }
                                )
                            }
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    pbScanning.visibility = View.GONE
                    btnScanNearby.isEnabled = true
                    btnScanNearby.text = "🔍 Scan Again"
                }
            }
        }
    }

    private lateinit var pbScanning: ProgressBar
    private lateinit var btnScanNearby: Button
    private lateinit var containerPaired: LinearLayout
    private lateinit var containerDiscovered: LinearLayout
    private lateinit var containerDemo: LinearLayout

    @SuppressLint("MissingPermission")
    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_bluetooth_devices, null)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCancelable(true)

        pbScanning = view.findViewById(R.id.pbScanning)
        btnScanNearby = view.findViewById(R.id.btnScanNearby)
        containerPaired = view.findViewById(R.id.containerPairedDevices)
        containerDiscovered = view.findViewById(R.id.containerDiscoveredDevices)
        containerDemo = view.findViewById(R.id.containerDemoDevices)

        view.findViewById<Button>(R.id.btnCloseDialog).setOnClickListener {
            cleanup()
            dialog.dismiss()
        }

        populatePairedDevices()
        populateDemoMerchants()

        btnScanNearby.setOnClickListener {
            startDiscovery()
        }

        dialog.setOnDismissListener {
            cleanup()
        }

        dialog.show()
    }

    @SuppressLint("MissingPermission")
    private fun populatePairedDevices() {
        containerPaired.removeAllViews()
        val pairedDevices = bluetoothEngine.getPairedDevices()

        if (pairedDevices.isEmpty()) {
            val emptyTv = TextView(context).apply {
                text = "No paired devices found. Pair both phones once in Bluetooth Settings for instant 1-tap connection:"
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                textSize = 11f
                setPadding(8, 4, 8, 8)
            }
            val btnOpenSettings = Button(context).apply {
                text = "⚙️ Open Bluetooth Settings to Pair"
                textSize = 11f
                setTextColor(Color.WHITE)
                backgroundTintList = ContextCompat.getColorStateList(context, R.color.primary)
                setOnClickListener {
                    try {
                        context.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            containerPaired.addView(emptyTv)
            containerPaired.addView(btnOpenSettings)
        } else {
            var empayPairedCount = 0
            pairedDevices.forEach { device ->
                val peer = BluetoothHelper.parseEmPayPeer(context, device) ?: return@forEach
                empayPairedCount++
                val displayName = peer.name
                val displaySub = if (peer.phone.isNotEmpty()) "${peer.phone} • EmPay Peer" else "${device.address} • EmPay Hardware"
                addDeviceItem(
                    container = containerPaired,
                    name = displayName,
                    address = displaySub,
                    badgeText = "Connect",
                    onClick = {
                        cleanup()
                        dialog.dismiss()
                        onDeviceSelected(displayName, device, false)
                    }
                )
            }
            if (empayPairedCount == 0) {
                val noEmpayTv = TextView(context).apply {
                    text = "No paired EmPay devices found. Tap 'Scan for Nearby EmPay Devices' below:"
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    textSize = 11f
                    setPadding(8, 4, 8, 8)
                }
                containerPaired.addView(noEmpayTv)
            }
        }
    }

    private fun populateDemoMerchants() {
        containerDemo.removeAllViews()
        val demoMerchants = listOf(
            "Apex City Care Pharmacy" to "Emergency Medical Supplies",
            "Daily Fresh Supermarket" to "Essential Food & Rations",
            "LifeLine Emergency Medicos" to "24/7 Disaster Relief",
            "Priya Sharma" to "Peer Device (Phone 2 Demo)",
            "Sukhpal Singh" to "Peer Device (Phone 2 Demo)",
            "City QuickFix Electrical Services" to "Power & Grid Repairs"
        )

        demoMerchants.forEach { (name, category) ->
            addDeviceItem(
                container = containerDemo,
                name = name,
                address = "$category · SPP Loopback",
                badgeText = "Select",
                onClick = {
                    cleanup()
                    dialog.dismiss()
                    onDeviceSelected(name, null, true)
                }
            )
        }
    }

    private fun addDeviceItem(
        container: LinearLayout,
        name: String,
        address: String,
        badgeText: String,
        onClick: () -> Unit
    ) {
        val itemView = LayoutInflater.from(context).inflate(R.layout.item_bluetooth_device, container, false)
        val tvName = itemView.findViewById<TextView>(R.id.tvDeviceName)
        val tvAddress = itemView.findViewById<TextView>(R.id.tvDeviceAddress)
        val tvBadge = itemView.findViewById<TextView>(R.id.tvDeviceBadge)

        tvName.text = name
        tvAddress.text = address
        tvBadge.text = badgeText

        itemView.setOnClickListener { onClick() }
        container.addView(itemView)
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        if (bluetoothAdapter == null) {
            btnScanNearby.text = "Bluetooth Not Supported"
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            btnScanNearby.text = "Bluetooth Disabled: Enable in Settings"
            return
        }

        // Check runtime permissions before triggering discovery
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasScan = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            val hasConnect = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            if (!hasScan || !hasConnect) {
                btnScanNearby.text = "⚠️ Grant Bluetooth Permissions"
                onRequestPermissions?.invoke()
                return
            }
        } else {
            val hasLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!hasLocation) {
                btnScanNearby.text = "⚠️ Grant Location Permission"
                onRequestPermissions?.invoke()
                return
            }
        }

        pbScanning.visibility = View.VISIBLE
        btnScanNearby.isEnabled = false
        btnScanNearby.text = "Scanning for nearby radios..."

        try {
            if (!isReceiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_FOUND)
                    addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                }
                ContextCompat.registerReceiver(
                    context,
                    discoveryReceiver,
                    filter,
                    ContextCompat.RECEIVER_EXPORTED
                )
                isReceiverRegistered = true
            }

            if (bluetoothAdapter.isDiscovering) {
                bluetoothAdapter.cancelDiscovery()
            }
            bluetoothAdapter.startDiscovery()
        } catch (e: Exception) {
            pbScanning.visibility = View.GONE
            btnScanNearby.isEnabled = true
            btnScanNearby.text = "⚠️ Scan Failed (Tap to Retry)"
        }
    }

    @SuppressLint("MissingPermission")
    private fun cleanup() {
        try {
            if (bluetoothAdapter?.isDiscovering == true) {
                bluetoothAdapter.cancelDiscovery()
            }
            if (isReceiverRegistered) {
                context.unregisterReceiver(discoveryReceiver)
                isReceiverRegistered = false
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

