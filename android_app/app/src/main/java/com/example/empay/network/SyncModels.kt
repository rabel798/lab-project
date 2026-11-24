package com.example.empay.network

import com.google.gson.annotations.SerializedName

/**
 * Single offline transaction item sent to the Central Bank for post-outage reconciliation.
 */
data class SyncTransactionItem(
    @SerializedName("tx_id") val txId: String,
    @SerializedName("payee") val payee: String,
    @SerializedName("amount") val amount: Double,
    @SerializedName("note") val note: String = "Bluetooth Mesh Offline Payment",
    @SerializedName("direction") val direction: String = "SENT",
    @SerializedName("sender_id") val senderId: String = "1",
    @SerializedName("receiver_id") val receiverId: String? = null,
    @SerializedName("channel") val channel: String = "Bluetooth"
)

/**
 * Batch payload of pending offline transactions uploaded by Android WorkManager or manual sync.
 */
data class SyncTransactionsRequest(
    @SerializedName("transactions") val transactions: List<SyncTransactionItem>,
    @SerializedName("user_id") val userId: Long? = null
)

/**
 * Response returned from Central Bank after reconciling offline mesh receipts.
 */
data class SyncTransactionsResponse(
    @SerializedName("status") val status: String,
    @SerializedName("message") val message: String,
    @SerializedName("reconciled_count") val reconciledCount: Int,
    @SerializedName("balance") val balance: Double,
    @SerializedName("emergency_balance") val emergencyBalance: Double
)

data class BankRemoteTransaction(
    @SerializedName("id") val id: Long? = null,
    @SerializedName("tx_id") val txId: String? = null,
    @SerializedName("sender_id") val senderId: Any? = null,
    @SerializedName("receiver_id") val receiverId: Any? = null,
    @SerializedName("payee") val payee: String? = null,
    @SerializedName("amount") val amount: Double = 0.0,
    @SerializedName("status") val status: String? = null,
    @SerializedName("channel") val channel: String? = null,
    @SerializedName("note") val note: String? = null,
    @SerializedName("is_offline") val isOffline: Int? = null,
    @SerializedName("method") val method: String? = null,
    @SerializedName("created_at") val createdAt: String? = null
)

