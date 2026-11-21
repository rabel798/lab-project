package com.example.empay.auth

import android.content.Context
import android.content.SharedPreferences
import com.example.empay.db.AppDatabase
import com.example.empay.db.WalletEntity
import com.example.empay.network.BankApiClient
import com.example.empay.network.NetworkManager
import com.example.empay.network.RegisterUserRequest
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Account model representing a registered user profile.
 */
data class UserAccount(
    val id: Long,
    val name: String,
    val phone: String,
    val password: String,
    val accountNumber: String,
    var balance: Double = 500.0,
    var emergencyBalance: Double = 100.0
)

/**
 * Manages user accounts, authentication (Rahul Sharma / rahul@12345),
 * and dynamic sign-up for multi-device Bluetooth peer testing.
 */
class AuthManager private constructor(private val context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_ACCOUNTS, Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val PREF_ACCOUNTS = "empay_registered_accounts"
        private const val KEY_ACCOUNTS_JSON = "accounts_list"
        private const val KEY_LOGGED_IN_ID = "logged_in_user_id"

        @Volatile
        private var instance: AuthManager? = null

        fun getInstance(context: Context): AuthManager {
            return instance ?: synchronized(this) {
                instance ?: AuthManager(context.applicationContext).also { instance = it }
            }
        }
    }

    init {
        ensureDefaultUsersExist()
    }

    private fun ensureDefaultUsersExist() {
        val accounts = getRegisteredAccounts().toMutableList()
        var updated = false

        // 1. Official Evaluation Profile: Rahul Sharma (password: rahul@12345)
        if (accounts.none { it.phone == "9876543210" || it.name.equals("Rahul Sharma", ignoreCase = true) }) {
            accounts.add(
                UserAccount(
                    id = 1L,
                    name = "Rahul Sharma",
                    phone = "9876543210",
                    password = "rahul@12345",
                    accountNumber = "ACC-9876543210-01",
                    balance = 1250.0,
                    emergencyBalance = 200.0
                )
            )
            updated = true
        }

        // 2. Pre-seeded Peer / Merchant: City Care Pharmacy (for demo Bluetooth receiving)
        if (accounts.none { it.name.equals("Apex City Care Pharmacy", ignoreCase = true) }) {
            accounts.add(
                UserAccount(
                    id = 101L,
                    name = "Apex City Care Pharmacy",
                    phone = "9811122233",
                    password = "pharmacy@123",
                    accountNumber = "ACC-9811122233-02",
                    balance = 5000.0,
                    emergencyBalance = 500.0
                )
            )
            updated = true
        }

        if (updated) {
            saveAccountsList(accounts)
        }
    }

    fun getRegisteredAccounts(): List<UserAccount> {
        val json = prefs.getString(KEY_ACCOUNTS_JSON, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<UserAccount>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveAccountsList(accounts: List<UserAccount>) {
        val json = gson.toJson(accounts)
        prefs.edit().putString(KEY_ACCOUNTS_JSON, json).apply()
    }

    /**
     * Authenticates an existing user profile against phone/name and password.
     */
    fun login(phoneOrName: String, password: String): UserAccount? {
        val trimmed = phoneOrName.trim()
        val account = getRegisteredAccounts().find {
            (it.phone.equals(trimmed, ignoreCase = true) || it.name.equals(trimmed, ignoreCase = true)) &&
                    it.password == password.trim()
        }

        if (account != null) {
            setActiveAccount(account)
        }
        return account
    }

    /**
     * Signs up a new user profile so testers can run Bluetooth discovery between two devices.
     */
    fun signUp(name: String, phone: String, password: String, emergencyBal: Double = 100.0): UserAccount {
        val accounts = getRegisteredAccounts().toMutableList()

        // Check if phone already registered; if so, update password and return
        val existingIndex = accounts.indexOfFirst { it.phone == phone.trim() }
        val account: UserAccount
        if (existingIndex >= 0) {
            account = accounts[existingIndex].copy(name = name.trim(), password = password.trim(), emergencyBalance = emergencyBal)
            accounts[existingIndex] = account
        } else {
            val newId = System.currentTimeMillis() % 1000000 + 100
            val cleanPhone = phone.trim()
            val accNum = "ACC-$cleanPhone-${(accounts.size + 1).toString().padStart(2, '0')}"
            account = UserAccount(
                id = newId,
                name = name.trim(),
                phone = cleanPhone,
                password = password.trim(),
                accountNumber = accNum,
                balance = 1000.0,
                emergencyBalance = emergencyBal
            )
            accounts.add(account)
        }

        saveAccountsList(accounts)
        setActiveAccount(account)

        // Automatically register with Central Bank server if online, crediting ₹1000.00 bank balance
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (NetworkManager.isOnline(context)) {
                    val api = BankApiClient.getApiService(context)
                    api.registerUser(
                        RegisterUserRequest(
                            name = account.name,
                            phone = account.phone,
                            balance = account.balance,
                            emergencyBalance = account.emergencyBalance
                        )
                    )
                }
            } catch (e: Exception) {
                // Offline fallback - offline wallet and local session are already credited
            }
        }

        return account
    }

    fun setActiveAccount(account: UserAccount) {
        prefs.edit().putLong(KEY_LOGGED_IN_ID, account.id).apply()

        // Update SessionManager
        val session = UserSession(
            id = account.id,
            name = account.name,
            phone = account.phone,
            accountNumber = account.accountNumber,
            balance = account.balance,
            emergencyBalance = account.emergencyBalance,
            isOnline = true
        )
        SessionManager.getInstance(context).updateUser(session)

        // Sync with local offline SQLite Wallet
        val db = AppDatabase.getInstance(context)
        val wallet = db.walletDao.getWallet()
        if (wallet == null) {
            db.walletDao.saveWallet(
                WalletEntity(
                    id = 1,
                    userId = account.id.toString(),
                    userName = account.name,
                    balance = account.emergencyBalance,
                    offlineMaxLimit = 5000.0,
                    emergencyThreshold = 50.0,
                    bankPublicKey = "PUB_KEY_DEMO",
                    lastSyncTimestamp = System.currentTimeMillis()
                )
            )
        } else {
            db.walletDao.updateBalance(account.emergencyBalance)
        }
    }

    fun getActiveAccount(): UserAccount? {
        val id = prefs.getLong(KEY_LOGGED_IN_ID, -1L)
        if (id == -1L) return null
        return getRegisteredAccounts().find { it.id == id }
    }

    fun isLoggedIn(): Boolean {
        return getActiveAccount() != null
    }

    fun logout() {
        prefs.edit().remove(KEY_LOGGED_IN_ID).apply()
    }
}
