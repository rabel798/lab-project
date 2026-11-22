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

/**
 * Payload sent to Laptop Bank to top up / add money to online bank balance.
 */
data class AddFundsRequest(
    @SerializedName("user_id") val userId: Long = 1L,
    @SerializedName("amount") val amount: Double,
    @SerializedName("phone") val phone: String? = null,
    @SerializedName("username") val username: String? = null
)

data class AddFundsResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String,
    @SerializedName("balance") val balance: Double,
    @SerializedName("emergency_balance") val emergencyBalance: Double? = null
)

data class UpdateUserNameRequest(
    @SerializedName("user_id") val userId: Long,
    @SerializedName("phone") val phone: String,
    @SerializedName("name") val name: String
)

data class UpdateUserNameResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String,
    @SerializedName("user") val user: BankUserResponse? = null
)
