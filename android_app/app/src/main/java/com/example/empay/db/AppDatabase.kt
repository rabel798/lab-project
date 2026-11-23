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
        private const val DATABASE_VERSION = 2

        private const val TABLE_WALLET = "wallet"
        private const val TABLE_TRANSACTIONS = "transactions"
        private const val TABLE_USERS = "users"

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
                direction TEXT NOT NULL,
                channel TEXT DEFAULT 'Bluetooth'
            );
        """.trimIndent()

        val createUsersTable = """
            CREATE TABLE IF NOT EXISTS $TABLE_USERS (
                id INTEGER PRIMARY KEY,
                username TEXT,
                name TEXT NOT NULL,
                phone TEXT NOT NULL,
                password TEXT NOT NULL,
                account_number TEXT,
                balance REAL DEFAULT 1000.0,
                emergency_balance REAL DEFAULT 100.0,
                is_remembered INTEGER DEFAULT 0,
                previous_names TEXT DEFAULT '',
                created_at INTEGER
            );
        """.trimIndent()

        db.execSQL(createWalletTable)
        db.execSQL(createTransactionsTable)
        db.execSQL(createUsersTable)

        val initialWallet = ContentValues().apply {
            put("id", 1L)
            put("user_id", "USER_1001")
            put("user_name", "")
            put("balance", 100.0)
            put("offline_max_limit", 5000.0)
            put("emergency_threshold", 50.0)
            put("bank_public_key", "")
            put("last_sync_timestamp", System.currentTimeMillis())
        }
        db.insert(TABLE_WALLET, null, initialWallet)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_USERS (
                id INTEGER PRIMARY KEY,
                username TEXT,
                name TEXT NOT NULL,
                phone TEXT NOT NULL,
                password TEXT NOT NULL,
                account_number TEXT,
                balance REAL DEFAULT 1000.0,
                emergency_balance REAL DEFAULT 100.0,
                is_remembered INTEGER DEFAULT 0,
                previous_names TEXT DEFAULT '',
                created_at INTEGER
            );
        """.trimIndent())
        val userCols = listOf(
            "username TEXT",
            "password TEXT DEFAULT '123456'",
            "account_number TEXT DEFAULT ''",
            "balance REAL DEFAULT 1000.0",
            "emergency_balance REAL DEFAULT 100.0",
            "is_remembered INTEGER DEFAULT 0",
            "previous_names TEXT DEFAULT ''",
            "created_at INTEGER"
        )
        for (col in userCols) {
            try {
                db.execSQL("ALTER TABLE $TABLE_USERS ADD COLUMN $col")
            } catch (_: Exception) {}
        }
        try {
            db.execSQL("ALTER TABLE $TABLE_TRANSACTIONS ADD COLUMN channel TEXT DEFAULT 'Bluetooth'")
        } catch (_: Exception) {}
        try {
            db.execSQL("UPDATE $TABLE_TRANSACTIONS SET channel = 'UPI' WHERE signature LIKE 'UPI%' OR tx_id LIKE '%UPI%'")
        } catch (_: Exception) {}
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Safe schema upgrade without wiping user accounts or wallet
        val userCols = listOf(
            "username TEXT",
            "password TEXT DEFAULT '123456'",
            "account_number TEXT DEFAULT ''",
            "balance REAL DEFAULT 1000.0",
            "emergency_balance REAL DEFAULT 100.0",
            "is_remembered INTEGER DEFAULT 0",
            "previous_names TEXT DEFAULT ''",
            "created_at INTEGER"
        )
        for (col in userCols) {
            try {
                db.execSQL("ALTER TABLE $TABLE_USERS ADD COLUMN $col")
            } catch (_: Exception) {}
        }
    }

    val userDao: UserDao = object : UserDao {
        override fun insertOrUpdateUser(user: com.example.empay.auth.UserAccount, isRemembered: Boolean): Long {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("id", user.id)
                put("username", user.name)
                put("name", user.name)
                put("phone", user.phone)
                put("password", user.password)
                put("account_number", user.accountNumber)
                put("balance", user.balance)
                put("emergency_balance", user.emergencyBalance)
                put("is_remembered", if (isRemembered) 1 else 0)
                put("previous_names", user.previousNames)
                put("created_at", System.currentTimeMillis())
            }
            return db.insertWithOnConflict(TABLE_USERS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }

        override fun getUserByUsernameOrPhone(query: String): com.example.empay.auth.UserAccount? {
            val trimmed = query.trim()
            val digits = trimmed.filter { it.isDigit() }.takeLast(10)
            val db = readableDatabase
            val cursor = db.rawQuery(
                """
                SELECT * FROM $TABLE_USERS 
                WHERE username = ? COLLATE NOCASE 
                   OR name = ? COLLATE NOCASE 
                   OR phone = ? 
                   OR (length(?) >= 7 AND phone LIKE ?)
                ORDER BY id ASC LIMIT 1
                """.trimIndent(),
                arrayOf(trimmed, trimmed, trimmed, digits, "%$digits%")
            )
            return cursor.use {
                if (it.moveToFirst()) cursorToUser(it) else null
            }
        }

        override fun getUserById(id: Long): com.example.empay.auth.UserAccount? {
            val db = readableDatabase
            val cursor = db.rawQuery("SELECT * FROM $TABLE_USERS WHERE id = ? LIMIT 1", arrayOf(id.toString()))
            return cursor.use {
                if (it.moveToFirst()) cursorToUser(it) else null
            }
        }

        override fun getAllUsers(): List<com.example.empay.auth.UserAccount> {
            val db = readableDatabase
            val cursor = db.rawQuery("SELECT * FROM $TABLE_USERS ORDER BY name ASC", null)
            val list = mutableListOf<com.example.empay.auth.UserAccount>()
            cursor.use {
                while (it.moveToNext()) {
                    list.add(cursorToUser(it))
                }
            }
            return list
        }

        override fun searchUsers(query: String): List<com.example.empay.auth.UserAccount> {
            val trimmed = query.trim()
            val db = readableDatabase
            val cursor = if (trimmed.isEmpty()) {
                db.rawQuery("SELECT * FROM $TABLE_USERS ORDER BY name ASC", null)
            } else {
                val digits = trimmed.filter { it.isDigit() }
                db.rawQuery(
                    """
                    SELECT * FROM $TABLE_USERS 
                    WHERE name LIKE ? OR username LIKE ? OR phone LIKE ? OR previous_names LIKE ?
                    ORDER BY name ASC
                    """.trimIndent(),
                    arrayOf("%$trimmed%", "%$trimmed%", "%$digits%", "%$trimmed%")
                )
            }
            val list = mutableListOf<com.example.empay.auth.UserAccount>()
            cursor.use {
                while (it.moveToNext()) {
                    list.add(cursorToUser(it))
                }
            }
            return list
        }

        override fun getRememberedUser(): com.example.empay.auth.UserAccount? {
            val db = readableDatabase
            val cursor = db.rawQuery("SELECT * FROM $TABLE_USERS WHERE is_remembered = 1 ORDER BY created_at DESC LIMIT 1", null)
            return cursor.use {
                if (it.moveToFirst()) cursorToUser(it) else null
            }
        }

        override fun setRemembered(userId: Long, isRemembered: Boolean) {
            val db = writableDatabase
            db.execSQL("UPDATE $TABLE_USERS SET is_remembered = 0")
            if (isRemembered) {
                db.execSQL("UPDATE $TABLE_USERS SET is_remembered = 1 WHERE id = ?", arrayOf(userId))
            }
        }

        override fun clearAllRemembered() {
            val db = writableDatabase
            db.execSQL("UPDATE $TABLE_USERS SET is_remembered = 0")
        }

        override fun updateUserName(userId: Long, newName: String, previousNames: String): Boolean {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("name", newName)
                put("username", newName)
                put("previous_names", previousNames)
            }
            val rows = db.update(TABLE_USERS, values, "id = ?", arrayOf(userId.toString()))
            return rows > 0
        }

        private fun cursorToUser(c: Cursor): com.example.empay.auth.UserAccount {
            fun getStringSafe(col: String, default: String = ""): String {
                val idx = c.getColumnIndex(col)
                return if (idx != -1 && !c.isNull(idx)) c.getString(idx) else default
            }
            fun getDoubleSafe(col: String, default: Double = 0.0): Double {
                val idx = c.getColumnIndex(col)
                return if (idx != -1 && !c.isNull(idx)) c.getDouble(idx) else default
            }
            fun getLongSafe(col: String, default: Long = 0L): Long {
                val idx = c.getColumnIndex(col)
                return if (idx != -1 && !c.isNull(idx)) c.getLong(idx) else default
            }

            val id = getLongSafe("id", 1L)
            val name = getStringSafe("name", "User")
            val phone = getStringSafe("phone", "")
            val pass = getStringSafe("password", "123456")
            val accNum = getStringSafe("account_number", if (phone.isNotEmpty()) "ACC-$phone-01" else "ACC-01")
            val bal = getDoubleSafe("balance", 1000.0)
            val emergBal = getDoubleSafe("emergency_balance", 100.0)
            val prevNames = getStringSafe("previous_names", "")

            return com.example.empay.auth.UserAccount(
                id = id,
                name = name,
                phone = phone,
                password = pass,
                accountNumber = accNum,
                balance = bal,
                emergencyBalance = emergBal,
                previousNames = prevNames
            )
        }
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
            val found = cursor.use {
                if (it.moveToFirst()) cursorToWallet(it) else null
            }
            if (found != null) return found

            // Auto-create wallet row id=1 if empty
            val initial = WalletEntity(
                id = 1L,
                userId = "1",
                userName = "Rahul Sharma",
                balance = 100.0,
                offlineMaxLimit = 5000.0,
                emergencyThreshold = 50.0,
                bankPublicKey = "",
                lastSyncTimestamp = System.currentTimeMillis()
            )
            saveWallet(initial)
            return initial
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
                put("last_sync_timestamp", System.currentTimeMillis())
            }
            val rows = db.update(TABLE_WALLET, values, "id = ?", arrayOf("1"))
            if (rows == 0) {
                values.put("id", 1L)
                values.put("user_id", "1")
                values.put("user_name", "Rahul Sharma")
                values.put("offline_max_limit", 5000.0)
                values.put("emergency_threshold", 50.0)
                values.put("bank_public_key", "")
                db.insertWithOnConflict(TABLE_WALLET, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }

        @Synchronized
        override fun deductBalance(amount: Double): Boolean {
            val db = writableDatabase
            db.beginTransaction()
            return try {
                val current = getWallet()
                val currentBal = current?.balance ?: 0.0
                if (currentBal >= amount) {
                    val newBalance = Math.round((currentBal - amount) * 100.0) / 100.0
                    val values = ContentValues().apply {
                        put("balance", newBalance)
                        put("last_sync_timestamp", System.currentTimeMillis())
                    }
                    val rows = db.update(TABLE_WALLET, values, "id = ?", arrayOf("1"))
                    if (rows == 0) {
                        values.put("id", 1L)
                        values.put("user_id", current?.userId ?: "1")
                        values.put("user_name", current?.userName ?: "User")
                        values.put("offline_max_limit", 5000.0)
                        values.put("emergency_threshold", 50.0)
                        values.put("bank_public_key", "")
                        db.insertWithOnConflict(TABLE_WALLET, null, values, SQLiteDatabase.CONFLICT_REPLACE)
                    }
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
                val currentBalance = current?.balance ?: 0.0
                val newBalance = Math.round((currentBalance + amount) * 100.0) / 100.0
                val values = ContentValues().apply {
                    put("balance", newBalance)
                    put("last_sync_timestamp", System.currentTimeMillis())
                }
                val rows = db.update(TABLE_WALLET, values, "id = ?", arrayOf("1"))
                if (rows == 0) {
                    values.put("id", 1L)
                    values.put("user_id", current?.userId ?: "1")
                    values.put("user_name", current?.userName ?: "User")
                    values.put("offline_max_limit", 5000.0)
                    values.put("emergency_threshold", 50.0)
                    values.put("bank_public_key", "")
                    db.insertWithOnConflict(TABLE_WALLET, null, values, SQLiteDatabase.CONFLICT_REPLACE)
                }
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
                put("channel", tx.channel)
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

        override fun clearAllTransactions(): Boolean {
            val db = writableDatabase
            return db.delete(TABLE_TRANSACTIONS, null, null) >= 0
        }

        private fun cursorToTransaction(cursor: Cursor): TransactionEntity {
            val channelCol = cursor.getColumnIndex("channel")
            val channelVal = if (channelCol != -1 && !cursor.isNull(channelCol)) {
                cursor.getString(channelCol)
            } else {
                TransactionEntity.CHANNEL_BLUETOOTH
            }

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
                direction = cursor.getString(cursor.getColumnIndexOrThrow("direction")),
                channel = channelVal
            )
        }
    }
}

