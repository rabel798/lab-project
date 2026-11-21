package com.example.empay.network

import com.google.gson.annotations.SerializedName

data class RegisterUserRequest(
    @SerializedName("name") val name: String,
    @SerializedName("phone") val phone: String,
    @SerializedName("balance") val balance: Double = 1000.0,
    @SerializedName("emergency_balance") val emergencyBalance: Double = 100.0
)

data class RegisterUserResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String,
    @SerializedName("user") val user: BankUserResponse?
)
