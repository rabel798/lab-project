package com.example.empay.db

data class WalletEntity(
    val id: Long = 1L,
    val userId: String,
    val userName: String,
    val balance: Double,
    val offlineMaxLimit: Double,
    val emergencyThreshold: Double,
    val bankPublicKey: String = "",
    val lastSyncTimestamp: Long = System.currentTimeMillis()
)
