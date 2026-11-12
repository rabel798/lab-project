package com.example.empay.network

import com.google.gson.annotations.SerializedName

/**
 * Single offline transaction item sent to the Central Bank for post-outage reconciliation.
 */
data class SyncTransactionItem(
    @SerializedName("tx_id") val txId: String,
    @SerializedName("payee") val payee: String,
    @SerializedName("amount") val amount: Double,
    @SerializedName("note") val note: String = "Bluetooth Mesh Offline Payment"
)

/**
 * Batch payload of pending offline transactions uploaded by Android WorkManager or manual sync.
 */
data class SyncTransactionsRequest(
    @SerializedName("transactions") val transactions: List<SyncTransactionItem>
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

