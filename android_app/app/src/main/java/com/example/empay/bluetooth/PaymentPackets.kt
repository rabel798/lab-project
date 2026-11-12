package com.example.empay.bluetooth

import com.google.gson.annotations.SerializedName

/**
 * Digitally signed offline payment packet transmitted over Bluetooth RFCOMM socket.
 */
data class PaymentPacket(
    @SerializedName("tx_id") val txId: String,
    @SerializedName("sender_id") val senderId: String,
    @SerializedName("sender_name") val senderName: String,
    @SerializedName("sender_public_key") val senderPublicKey: String,
    @SerializedName("receiver_id") val receiverId: String,
    @SerializedName("receiver_name") val receiverName: String,
    @SerializedName("amount") val amount: Double,
    @SerializedName("timestamp") val timestamp: Long,
    @SerializedName("nonce") val nonce: String,
    @SerializedName("signature") val signature: String,
    @SerializedName("note") val note: String = "Offline Emergency Payment"
)

/**
 * Mutual acknowledgment receipt returned by Merchant over RFCOMM socket upon signature & nonce verification.
 */
data class AckPacket(
    @SerializedName("tx_id") val txId: String,
    @SerializedName("status") val status: String, // "SUCCESS" or "REJECTED"
    @SerializedName("receiver_id") val receiverId: String,
    @SerializedName("receiver_name") val receiverName: String,
    @SerializedName("timestamp") val timestamp: Long,
    @SerializedName("ack_nonce") val ackNonce: String,
    @SerializedName("signature") val signature: String,
    @SerializedName("message") val message: String
)

