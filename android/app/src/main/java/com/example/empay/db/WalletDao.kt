package com.example.empay.db

interface WalletDao {
    fun getWallet(): WalletEntity?
    fun saveWallet(wallet: WalletEntity)
    fun updateBalance(newBalance: Double)
    fun deductBalance(amount: Double): Boolean
    fun addBalance(amount: Double)
}
