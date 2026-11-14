package com.example.empay.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Manages local, offline PIN authentication using hardware-backed EncryptedSharedPreferences.
 * Stores salted SHA-256 hash to authorize offline signing and payment without network connectivity.
 */
class PinAuthManager private constructor(context: Context) {

    private val prefs: SharedPreferences = createEncryptedPreferences(context)
    private val secureRandom = SecureRandom()

    companion object {
        private const val PREFS_FILE_NAME = "empay_secure_pin_store"
        private const val KEY_PIN_HASH = "sec_pin_hash"
        private const val KEY_PIN_SALT = "sec_pin_salt"
        const val DEFAULT_PIN = "1234"

        @Volatile
        private var INSTANCE: PinAuthManager? = null

        fun getInstance(context: Context): PinAuthManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PinAuthManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun computeHash(pin: String, salt: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(salt)
            val hash = digest.digest(pin.toByteArray(StandardCharsets.UTF_8))
            return Base64.getEncoder().encodeToString(hash)
        }

        private fun createEncryptedPreferences(context: Context): SharedPreferences {
            return try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

                EncryptedSharedPreferences.create(
                    context,
                    PREFS_FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                // Graceful fallback to private SharedPreferences if hardware keystore fails on legacy emulator
                context.getSharedPreferences("empay_fallback_pin_store", Context.MODE_PRIVATE)
            }
        }
    }

    init {
        // Initialize with default demo PIN (1234) if not already configured
        if (!isPinSet()) {
            setPin(DEFAULT_PIN)
        }
    }

    fun isPinSet(): Boolean {
        return prefs.contains(KEY_PIN_HASH) && prefs.contains(KEY_PIN_SALT)
    }

    @Synchronized
    fun setPin(pin: String): Boolean {
        if (pin.length != 4 || !pin.all { it.isDigit() }) {
            return false
        }

        val saltBytes = ByteArray(16)
        secureRandom.nextBytes(saltBytes)
        val saltBase64 = Base64.getEncoder().encodeToString(saltBytes)

        val hashBase64 = computeHash(pin, saltBytes)

        prefs.edit().apply {
            putString(KEY_PIN_SALT, saltBase64)
            putString(KEY_PIN_HASH, hashBase64)
            apply()
        }
        return true
    }

    @Synchronized
    fun verifyPin(pin: String): Boolean {
        if (pin.length != 4 || !pin.all { it.isDigit() }) {
            return false
        }

        val storedSaltBase64 = prefs.getString(KEY_PIN_SALT, null) ?: return false
        val storedHashBase64 = prefs.getString(KEY_PIN_HASH, null) ?: return false

        val saltBytes = Base64.getDecoder().decode(storedSaltBase64.trim())
        val computedHashBase64 = computeHash(pin, saltBytes)

        return MessageDigest.isEqual(
            storedHashBase64.toByteArray(StandardCharsets.UTF_8),
            computedHashBase64.toByteArray(StandardCharsets.UTF_8)
        )
    }

    fun resetPinToDefault() {
        setPin(DEFAULT_PIN)
    }
}

