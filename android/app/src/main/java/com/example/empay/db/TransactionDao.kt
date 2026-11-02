package com.example.empay.db

interface TransactionDao {
    fun insertTransaction(tx: TransactionEntity): Boolean
    fun getTransaction(txId: String): TransactionEntity?
    fun getAllTransactions(): List<TransactionEntity>
    fun getPendingSyncTransactions(): List<TransactionEntity>
    fun markAsSynced(txId: String): Boolean
    fun markAsRejected(txId: String): Boolean
    fun isNonceUsed(nonce: String): Boolean
}
