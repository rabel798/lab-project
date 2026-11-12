package com.example.empay.bluetooth

import java.util.UUID

object BluetoothConstants {
    // Standard Serial Port Profile (SPP) UUID
    const val SPP_UUID_STRING = "00001101-0000-1000-8000-00805F9B34FB"
    val SPP_UUID: UUID = UUID.fromString(SPP_UUID_STRING)

    const val SERVICE_NAME = "EmPayOfflinePayment"
    const val PACKET_DELIMITER = "\n---END_PACKET---\n"
}

