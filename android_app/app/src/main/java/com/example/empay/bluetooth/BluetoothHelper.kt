package com.example.empay.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.empay.auth.AuthManager

data class EmPayPeer(
    val id: Long,
    val name: String,
    val phone: String,
    val distanceMeters: Double = 0.5,
    val isMerchant: Boolean = false,
    val rawDevice: BluetoothDevice? = null
)

object BluetoothHelper {

    private const val PREFIX = "EmPay"
    private const val SEPARATOR = ":"

    /**
     * Updates the local Android Bluetooth Adapter name to broadcast identity:
     * Format: "EmPay:<RegisteredName>:<RegisteredPhone>"
     * E.g.: "EmPay:Rahul:9876543210"
     */
    @SuppressLint("MissingPermission")
    fun broadcastEmPayIdentity(context: Context, name: String, phone: String): Boolean {
        if (!hasConnectPermission(context)) return false
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
            if (!adapter.isEnabled) return false

            val cleanName = name.trim().replace(":", "").take(16)
            val cleanPhone = phone.trim().replace(":", "").takeLast(10)
            val broadcastName = "$PREFIX$SEPARATOR$cleanName$SEPARATOR$cleanPhone"

            adapter.name = broadcastName
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Inspects a Bluetooth device and determines if it is running the EmPay application.
     * Non-EmPay devices (headphones, cars, TVs, generic PCs) return null and are filtered out.
     */
    @SuppressLint("MissingPermission")
    fun parseEmPayPeer(context: Context, device: BluetoothDevice, extraName: String? = null): EmPayPeer? {
        if (!hasConnectPermission(context)) return null

        val deviceName = try {
            device.name?.trim() ?: ""
        } catch (_: Exception) {
            ""
        }

        val rawName = if (!extraName.isNullOrBlank()) extraName.trim() else deviceName
        if (rawName.isEmpty()) return null

        // 1. Check for EmPay broadcast protocol: "EmPay:<Name>:<Phone>" or "EmPay-<Name>"
        if (rawName.startsWith(PREFIX, ignoreCase = true)) {
            val delimiter = if (rawName.contains(":")) ":" else if (rawName.contains("-")) "-" else "_"
            val parts = rawName.split(delimiter)
            if (parts.size >= 3) {
                val peerName = parts[1].trim()
                val peerPhone = parts[2].trim()

                // Auto-cache discovered EmPay peer into local SQLite database so offline payments can verify them immediately
                try {
                    val db = com.example.empay.db.AppDatabase.getInstance(context)
                    if (peerPhone.isNotEmpty()) {
                        val existing = db.userDao.getUserByUsernameOrPhone(peerPhone)
                        if (existing == null) {
                            val newAcc = com.example.empay.auth.UserAccount(
                                id = Math.abs(device.address.hashCode().toLong()) % 100000 + 100,
                                name = peerName.ifEmpty { "EmPay Peer" },
                                phone = peerPhone,
                                password = "password",
                                accountNumber = "ACC-$peerPhone",
                                balance = 1000.0,
                                emergencyBalance = 100.0
                            )
                            db.userDao.insertOrUpdateUser(newAcc, false)
                        }
                    }
                } catch (_: Exception) {}

                return EmPayPeer(
                    id = device.address.hashCode().toLong(),
                    name = peerName.ifEmpty { "EmPay Peer" },
                    phone = peerPhone,
                    distanceMeters = 0.5,
                    isMerchant = false,
                    rawDevice = device
                )
            } else if (parts.size == 2) {
                val peerName = parts[1].trim()
                return EmPayPeer(
                    id = device.address.hashCode().toLong(),
                    name = peerName.ifEmpty { "EmPay Peer" },
                    phone = "",
                    distanceMeters = 0.5,
                    isMerchant = false,
                    rawDevice = device
                )
            }
        }

        // 2. Check for legacy EmPay format: "EmPay - <Name>"
        if (rawName.startsWith("EmPay - ", ignoreCase = true)) {
            val peerName = rawName.removePrefix("EmPay - ").trim()
            return EmPayPeer(
                id = device.address.hashCode().toLong(),
                name = peerName,
                phone = "",
                distanceMeters = 0.5,
                isMerchant = false,
                rawDevice = device
            )
        }

        // 3. Check if device matches any registered user account created in the app or database
        val registeredAccounts = AuthManager.getInstance(context).getRegisteredAccounts().toMutableList()
        val dbUsers = com.example.empay.db.AppDatabase.getInstance(context).userDao.getAllUsers()
        dbUsers.forEach { u ->
            if (registeredAccounts.none { it.id == u.id || it.phone == u.phone }) {
                registeredAccounts.add(u)
            }
        }

        val accountMatch = registeredAccounts.find { acc ->
            acc.name.equals(rawName, ignoreCase = true) ||
            rawName.contains(acc.name, ignoreCase = true) ||
            (acc.previousNames.isNotEmpty() && acc.previousNames.contains(rawName, ignoreCase = true)) ||
            (acc.phone.isNotEmpty() && rawName.contains(acc.phone))
        }
        if (accountMatch != null) {
            return EmPayPeer(
                id = device.address.hashCode().toLong(),
                name = accountMatch.name,
                phone = accountMatch.phone,
                distanceMeters = 0.5,
                isMerchant = false,
                rawDevice = device
            )
        }

        // 4. Check for Emergency Disaster-Relief Merchant Terminals
        val isMerchantTerminal = rawName.contains("Pharmacy", true) ||
                rawName.contains("Supermarket", true) ||
                rawName.contains("Medicos", true) ||
                rawName.contains("Stationery", true) ||
                rawName.contains("QuickFix", true) ||
                rawName.contains("Hospital", true)
        if (isMerchantTerminal) {
            return EmPayPeer(
                id = device.address.hashCode().toLong(),
                name = rawName,
                phone = "Paired Merchant",
                distanceMeters = 0.5,
                isMerchant = true,
                rawDevice = device
            )
        }

        // ALL OTHER DEVICES (Headphones, Car, TV, Laptop, unknown phones) -> EXCLUDE COMPLETELY
        return null
    }

    fun hasConnectPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun hasScanPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }
}
