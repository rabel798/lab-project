package com.example.empay.db

data class TransactionEntity(
    val txId: String,
    val senderId: String,
    val senderName: String,
    val receiverId: String,
    val receiverName: String,
    val amount: Double,
    val timestamp: Long,
    val nonce: String,
    val signature: String,
    val status: String = STATUS_PENDING_SYNC,
    val direction: String = DIRECTION_SENT,
    val channel: String = CHANNEL_BLUETOOTH
) {
    companion object {
        const val STATUS_PENDING_SYNC = "PENDING_SYNC"
        const val STATUS_SYNCED = "SYNCED"
        const val STATUS_REJECTED = "REJECTED"

        const val DIRECTION_SENT = "SENT"
        const val DIRECTION_RECEIVED = "RECEIVED"

        const val CHANNEL_BLUETOOTH = "Bluetooth"
        const val CHANNEL_UPI = "UPI"
    }

    val isOnline: Boolean
        get() = channel.equals(CHANNEL_UPI, ignoreCase = true) ||
                signature.startsWith("UPI", ignoreCase = true) ||
                txId.contains("UPI", ignoreCase = true)
}
