package com.example.empay.auth

/**
 * Data class representing the active user session in EmPay.
 */
data class UserSession(
    val id: Long = 1L,
    val name: String = "Rahul Sharma",
    val phone: String = "9876543210",
    val accountNumber: String = "ACC-9876543210-01",
    val balance: Double = 1250.0,
    val emergencyBalance: Double = 200.0,
    val isOnline: Boolean = true
) {
    companion object {
        val DEMO_USERS = listOf(
            UserSession(
                id = 1L,
                name = "Rahul Sharma",
                phone = "9876543210",
                accountNumber = "ACC-9876543210-01",
                balance = 1250.0,
                emergencyBalance = 200.0
            ),
            UserSession(
                id = 2L,
                name = "Sukhpal Singh",
                phone = "9811122233",
                accountNumber = "ACC-9811122233-02",
                balance = 1000.0,
                emergencyBalance = 100.0
            ),
            UserSession(
                id = 3L,
                name = "Priya Sharma",
                phone = "8765432109",
                accountNumber = "ACC-8765432109-03",
                balance = 1000.0,
                emergencyBalance = 150.0
            ),
            UserSession(
                id = 4L,
                name = "Dr. Rohan Verma",
                phone = "7654321098",
                accountNumber = "ACC-7654321098-04",
                balance = 1000.0,
                emergencyBalance = 100.0
            ),
            UserSession(
                id = 5L,
                name = "Ananya Patel",
                phone = "6543210987",
                accountNumber = "ACC-6543210987-05",
                balance = 1000.0,
                emergencyBalance = 100.0
            )
        )
    }
}
