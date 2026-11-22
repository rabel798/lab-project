package com.example.empay.network

import com.google.gson.annotations.SerializedName

/**
 * Data response mapping user profile and bank ledger balance from Laptop Bank server.
 */
data class BankUserResponse(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("phone") val phone: String,
    @SerializedName("balance") val balance: Double,
    @SerializedName("emergency_balance") val emergencyBalance: Double,
    @SerializedName("previous_names") val previousNames: String? = null
)
