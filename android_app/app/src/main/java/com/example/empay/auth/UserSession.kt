package com.example.empay.auth

/**
 * Data class representing the active user session in EmPay.
 * Defaults to the official evaluation user 'Rahul Sharma' (ID 1, Phone: 9876543210).
 */
data class UserSession(
    val id: Long = 1L,
    val name: String = "Rahul Sharma",
    val phone: String = "9876543210",
    val accountNumber: String = "ACC-9876543210-01",
    val balance: Double = 500.0,
    val emergencyBalance: Double = 10.0,
    val isOnline: Boolean = true
)

