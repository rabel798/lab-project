package com.example.empay.auth

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages active user authentication session, available bank balance,
 * and emergency offline wallet allocation.
 */
class SessionManager private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private val _sessionState = MutableStateFlow(loadSession())
    val sessionState: StateFlow<UserSession> = _sessionState.asStateFlow()

    companion object {
        private const val PREF_NAME = "empay_user_session"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_NAME = "name"
        private const val KEY_PHONE = "phone"
        private const val KEY_ACCOUNT_NUMBER = "account_number"
        private const val KEY_BALANCE = "balance"
        private const val KEY_EMERGENCY_BALANCE = "emergency_balance"
        private const val KEY_IS_ONLINE = "is_online"

        @Volatile
        private var INSTANCE: SessionManager? = null

        fun getInstance(context: Context): SessionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SessionManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private fun loadSession(): UserSession {
        val id = prefs.getLong(KEY_USER_ID, 1L)
        val name = prefs.getString(KEY_NAME, "Rahul Sharma") ?: "Rahul Sharma"
        val phone = prefs.getString(KEY_PHONE, "9876543210") ?: "9876543210"
        val accountNumber = prefs.getString(KEY_ACCOUNT_NUMBER, "ACC-9876543210-01") ?: "ACC-9876543210-01"
        val balance = prefs.getFloat(KEY_BALANCE, 500.0f).toDouble()
        val emergencyBalance = prefs.getFloat(KEY_EMERGENCY_BALANCE, 10.0f).toDouble()
        val isOnline = prefs.getBoolean(KEY_IS_ONLINE, true)

        return UserSession(
            id = id,
            name = name,
            phone = phone,
            accountNumber = accountNumber,
            balance = balance,
            emergencyBalance = emergencyBalance,
            isOnline = isOnline
        )
    }

    fun getUser(): UserSession = _sessionState.value

    fun updateUser(session: UserSession) {
        prefs.edit().apply {
            putLong(KEY_USER_ID, session.id)
            putString(KEY_NAME, session.name)
            putString(KEY_PHONE, session.phone)
            putString(KEY_ACCOUNT_NUMBER, session.accountNumber)
            putFloat(KEY_BALANCE, session.balance.toFloat())
            putFloat(KEY_EMERGENCY_BALANCE, session.emergencyBalance.toFloat())
            putBoolean(KEY_IS_ONLINE, session.isOnline)
            apply()
        }
        _sessionState.value = session
    }

    fun updateBalances(onlineBalance: Double, emergencyBalance: Double) {
        val current = _sessionState.value
        val updated = current.copy(
            balance = onlineBalance,
            emergencyBalance = emergencyBalance
        )
        updateUser(updated)
    }

    fun updateOnlineStatus(isOnline: Boolean) {
        val current = _sessionState.value
        val updated = current.copy(isOnline = isOnline)
        updateUser(updated)
    }

    fun resetToDefaultDemoUser() {
        val defaultUser = UserSession()
        updateUser(defaultUser)
    }
}

