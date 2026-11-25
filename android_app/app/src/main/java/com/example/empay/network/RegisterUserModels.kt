package com.example.empay.network

import com.google.gson.annotations.SerializedName

data class RegisterUserRequest(
    @SerializedName("name") val name: String,
    @SerializedName("phone") val phone: String,
    @SerializedName("username") val username: String = name,
    @SerializedName("password") val password: String = "123456",
    @SerializedName("balance") val balance: Double = 1000.0,
    @SerializedName("emergency_balance") val emergencyBalance: Double = 100.0,
    @SerializedName("device_id") val deviceId: String = ""
)

data class RegisterUserResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String,
    @SerializedName("user") val user: BankUserResponse?,
    @SerializedName("error_code") val errorCode: String? = null
)

data class LoginRequest(
    @SerializedName("username") val username: String,
    @SerializedName("password") val password: String,
    @SerializedName("device_id") val deviceId: String
)

data class LoginResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String,
    @SerializedName("user") val user: BankUserResponse?,
    @SerializedName("error_code") val errorCode: String? = null
)

data class LogoutRequest(
    @SerializedName("user_id") val userId: Long? = null,
    @SerializedName("phone") val phone: String? = null,
    @SerializedName("device_id") val deviceId: String = ""
)

data class LogoutResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String
)

data class VerifySessionResponse(
    @SerializedName("valid") val valid: Boolean,
    @SerializedName("message") val message: String? = null
)
