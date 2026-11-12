package com.example.empay.network

import com.google.gson.annotations.SerializedName

/**
 * Payload sent to Laptop Bank to reserve offline emergency balance from online balance.
 */
data class TransferEmergencyRequest(
    @SerializedName("user_id") val userId: Long = 1L,
    @SerializedName("amount") val amount: Double
)

/**
 * Response received from Laptop Bank upon successful balance reservation.
 */
data class TransferEmergencyResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String,
    @SerializedName("balance") val balance: Double,
    @SerializedName("emergency_balance") val emergencyBalance: Double
)

