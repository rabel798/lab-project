package com.example.empay.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class AppDatabase private constructor(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {

    companion object {
        private const val DATABASE_NAME = "empay_offline.db"
        private const val DATABASE_VERSION = 1

        private const val TABLE_WALLET = "wallet"
        private const val TABLE_TRANSACTIONS = "transactions"

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: AppDatabase(context.applicationContext).also { instance = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createWalletTable = """
            CREATE TABLE IF NOT EXISTS $TABLE_WALLET (
                id INTEGER PRIMARY KEY,
                user_id TEXT NOT NULL,
                user_name TEXT NOT NULL,
                balance REAL NOT NULL,
                offline_max_limit REAL NOT NULL,
                emergency_threshold REAL NOT NULL,
                bank_public_key TEXT NOT NULL,
                last_sync_timestamp INTEGER NOT NULL
            );
        """.trimIndent()

        val createTransactionsTable = """
            CREATE TABLE IF NOT EXISTS $TABLE_TRANSACTIONS (
                tx_id TEXT PRIMARY KEY,
                sender_id TEXT NOT NULL,
                sender_name TEXT NOT NULL,
                receiver_id TEXT NOT NULL,
                receiver_name TEXT NOT NULL,
                amount REAL NOT NULL,
                timestamp INTEGER NOT NULL,
                nonce TEXT NOT NULL UNIQUE,
                signature TEXT NOT NULL,
                status TEXT NOT NULL,
                direction TEXT NOT NULL
            );
        """.trimIndent()

        db.execSQL(createWalletTable)
        db.execSQL(createTransactionsTable)

        // Pre-populate with a default emergency offline balance
        val initialWallet = ContentValues().apply {
            put("id", 1L)
            put("user_id", "USER_" + (1000..9999).random())
            put("user_name", "Rahul Sharma")
            put("balance", 10.0)
            put("offline_max_limit", 10.0)
            put("emergency_threshold", 5.0)
            put("bank_public_key", "")
            put("last_sync_timestamp", System.currentTimeMillis())
        }
        db.insert(TABLE_WALLET, null, initialWallet)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_WALLET")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_TRANSACTIONS")
        onCreate(db)
    }

    val walletDao: WalletDao = object : WalletDao {
        override fun getWallet(): WalletEntity? {
            val db = readableDatabase
            val cursor = db.query(
                TABLE_WALLET,
                null,
                "id = ?",
                arrayOf("1"),
                null,
                null,
                null
            )
            return cursor.use {
                if (it.moveToFirst()) cursorToWallet(it) else null
            }
        }

        override fun saveWallet(wallet: WalletEntity) {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("id", 1L)
                put("user_id", wallet.userId)
                put("user_name", wallet.userName)
                put("balance", wallet.balance)
                put("offline_max_limit", wallet.offlineMaxLimit)
                put("emergency_threshold", wallet.emergencyThreshold)
                put("bank_public_key", wallet.bankPublicKey)
                put("last_sync_timestamp", wallet.lastSyncTimestamp)
            }
            db.insertWithOnConflict(TABLE_WALLET, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }

        override fun updateBalance(newBalance: Double) {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("balance", newBalance)
            }
            db.update(TABLE_WALLET, values, "id = ?", arrayOf("1"))
        }

        @Synchronized
        override fun deductBalance(amount: Double): Boolean {
            val db = writableDatabase
            db.beginTransaction()
            return try {
                val current = getWallet()
                if (current != null && current.balance >= amount) {
                    val newBalance = current.balance - amount
                    val values = ContentValues().apply {
                        put("balance", newBalance)
                    }
                    db.update(TABLE_WALLET, values, "id = ?", arrayOf("1"))
                    db.setTransactionSuccessful()
                    true
                } else {
                    false
                }
            } finally {
                db.endTransaction()
            }
        }

        @Synchronized
        override fun addBalance(amount: Double) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                val current = getWallet()
                val newBalance = (current?.balance ?: 0.0) + amount
                val values = ContentValues().apply {
                    put("balance", newBalance)
                }
                db.update(TABLE_WALLET, values, "id = ?", arrayOf("1"))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        private fun cursorToWallet(cursor: Cursor): WalletEntity {
            return WalletEntity(
                id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                userId = cursor.getString(cursor.getColumnIndexOrThrow("user_id")),
                userName = cursor.getString(cursor.getColumnIndexOrThrow("user_name")),
                balance = cursor.getDouble(cursor.getColumnIndexOrThrow("balance")),
                offlineMaxLimit = cursor.getDouble(cursor.getColumnIndexOrThrow("offline_max_limit")),
                emergencyThreshold = cursor.getDouble(cursor.getColumnIndexOrThrow("emergency_threshold")),
                bankPublicKey = cursor.getString(cursor.getColumnIndexOrThrow("bank_public_key")),
                lastSyncTimestamp = cursor.getLong(cursor.getColumnIndexOrThrow("last_sync_timestamp"))
            )
        }
    }

    val transactionDao: TransactionDao = object : TransactionDao {
        override fun insertTransaction(tx: TransactionEntity): Boolean {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("tx_id", tx.txId)
                put("sender_id", tx.senderId)
                put("sender_name", tx.senderName)
                put("receiver_id", tx.receiverId)
                put("receiver_name", tx.receiverName)
                put("amount", tx.amount)
                put("timestamp", tx.timestamp)
                put("nonce", tx.nonce)
                put("signature", tx.signature)
                put("status", tx.status)
                put("direction", tx.direction)
            }
            val rowId = db.insertWithOnConflict(TABLE_TRANSACTIONS, null, values, SQLiteDatabase.CONFLICT_IGNORE)
            return rowId != -1L
        }

        override fun getTransaction(txId: String): TransactionEntity? {
            val db = readableDatabase
            val cursor = db.query(
                TABLE_TRANSACTIONS,
                null,
                "tx_id = ?",
                arrayOf(txId),
                null,
                null,
                null
            )
            return cursor.use {
                if (it.moveToFirst()) cursorToTransaction(it) else null
            }
        }

        override fun getAllTransactions(): List<TransactionEntity> {
            val db = readableDatabase
            val list = mutableListOf<TransactionEntity>()
            val cursor = db.query(
                TABLE_TRANSACTIONS,
                null,
                null,
                null,
                null,
                null,
                "timestamp DESC"
            )
            cursor.use {
                while (it.moveToNext()) {
                    list.add(cursorToTransaction(it))
                }
            }
            return list
        }

        override fun getPendingSyncTransactions(): List<TransactionEntity> {
            val db = readableDatabase
            val list = mutableListOf<TransactionEntity>()
            val cursor = db.query(
                TABLE_TRANSACTIONS,
                null,
                "status = ?",
                arrayOf(TransactionEntity.STATUS_PENDING_SYNC),
                null,
                null,
                "timestamp ASC"
            )
            cursor.use {
                while (it.moveToNext()) {
                    list.add(cursorToTransaction(it))
                }
            }
            return list
        }

        override fun markAsSynced(txId: String): Boolean {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("status", TransactionEntity.STATUS_SYNCED)
            }
            return db.update(TABLE_TRANSACTIONS, values, "tx_id = ?", arrayOf(txId)) > 0
        }

        override fun markAsRejected(txId: String): Boolean {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("status", TransactionEntity.STATUS_REJECTED)
            }
            return db.update(TABLE_TRANSACTIONS, values, "tx_id = ?", arrayOf(txId)) > 0
        }

        override fun isNonceUsed(nonce: String): Boolean {
            val db = readableDatabase
            val cursor = db.query(
                TABLE_TRANSACTIONS,
                arrayOf("tx_id"),
                "nonce = ?",
                arrayOf(nonce),
                null,
                null,
                null
            )
            return cursor.use { it.count > 0 }
        }

        private fun cursorToTransaction(cursor: Cursor): TransactionEntity {
            return TransactionEntity(
                txId = cursor.getString(cursor.getColumnIndexOrThrow("tx_id")),
                senderId = cursor.getString(cursor.getColumnIndexOrThrow("sender_id")),
                senderName = cursor.getString(cursor.getColumnIndexOrThrow("sender_name")),
                receiverId = cursor.getString(cursor.getColumnIndexOrThrow("receiver_id")),
                receiverName = cursor.getString(cursor.getColumnIndexOrThrow("receiver_name")),
                amount = cursor.getDouble(cursor.getColumnIndexOrThrow("amount")),
                timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
                nonce = cursor.getString(cursor.getColumnIndexOrThrow("nonce")),
                signature = cursor.getString(cursor.getColumnIndexOrThrow("signature")),
                status = cursor.getString(cursor.getColumnIndexOrThrow("status")),
                direction = cursor.getString(cursor.getColumnIndexOrThrow("direction"))
            )
        }
    }
}
