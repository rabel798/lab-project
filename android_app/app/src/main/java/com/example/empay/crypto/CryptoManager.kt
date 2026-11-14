package com.example.empay.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Locale
import java.util.UUID

/**
 * Hardware-backed Cryptographic Engine for EmPay.
 * Generates Elliptic Curve keys (secp256r1) inside AndroidKeyStore (TEE/StrongBox),
 * generates anti-replay nonces, canonicalizes transaction payloads, and produces
 * SHA256withECDSA digital signatures for offline peer transactions.
 */
object CryptoManager {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "EmPayOfflineKey"
    private const val EC_CURVE = "secp256r1"
    private const val SIGNATURE_ALGO = "SHA256withECDSA"

    private val secureRandom = SecureRandom()
    private var fallbackKeyPair: KeyPair? = null

    fun getOrCreateKeyPair(): KeyPair {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

            if (keyStore.containsAlias(KEY_ALIAS)) {
                val privateKeyEntry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
                if (privateKeyEntry != null) {
                    return KeyPair(privateKeyEntry.certificate.publicKey, privateKeyEntry.privateKey)
                }
            }

            val keyPairGenerator = KeyPairGenerator.getInstance(
                "EC",
                ANDROID_KEYSTORE
            )

            val parameterSpec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec(EC_CURVE))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()

            keyPairGenerator.initialize(parameterSpec)
            keyPairGenerator.generateKeyPair()
        } catch (e: Throwable) {
            // Host JVM / Unit Test fallback when AndroidKeyStore is not present
            getOrCreateFallbackKeyPair()
        }
    }

    private fun getOrCreateFallbackKeyPair(): KeyPair {
        if (fallbackKeyPair == null) {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec(EC_CURVE), secureRandom)
            fallbackKeyPair = kpg.generateKeyPair()
        }
        return fallbackKeyPair!!
    }

    fun getPublicKeyBase64(): String {
        val keyPair = getOrCreateKeyPair()
        return Base64.getEncoder().encodeToString(keyPair.public.encoded)
    }

    fun getPublicKeyFingerprint(): String {
        return try {
            val keyPair = getOrCreateKeyPair()
            val md = MessageDigest.getInstance("SHA-256")
            val hash = md.digest(keyPair.public.encoded)
            val hex = hash.joinToString("") { "%02x".format(it) }
            "secp256r1:" + hex.substring(0, 8) + "..." + hex.substring(hex.length - 8)
        } catch (e: Exception) {
            "secp256r1:active"
        }
    }

    fun getCanonicalBytes(
        txId: String,
        senderId: String,
        receiverId: String,
        amount: Double,
        timestamp: Long,
        nonce: String
    ): ByteArray {
        val canonicalString = String.format(
            Locale.US,
            "%s|%s|%s|%.2f|%d|%s",
            txId.trim(),
            senderId.trim(),
            receiverId.trim(),
            amount,
            timestamp,
            nonce.trim()
        )
        return canonicalString.toByteArray(StandardCharsets.UTF_8)
    }

    fun signData(data: ByteArray): String {
        val keyPair = getOrCreateKeyPair()
        val signature = Signature.getInstance(SIGNATURE_ALGO).apply {
            initSign(keyPair.private)
            update(data)
        }
        val signedBytes = signature.sign()
        return Base64.getEncoder().encodeToString(signedBytes)
    }

    fun verifySignature(
        publicKeyBase64: String,
        data: ByteArray,
        signatureBase64: String
    ): Boolean {
        return try {
            val decodedPubKeyBytes = Base64.getDecoder().decode(publicKeyBase64.trim())
            val keySpec = X509EncodedKeySpec(decodedPubKeyBytes)
            val keyFactory = KeyFactory.getInstance("EC")
            val publicKey: PublicKey = keyFactory.generatePublic(keySpec)

            val signatureBytes = Base64.getDecoder().decode(signatureBase64.trim())
            val signature = Signature.getInstance(SIGNATURE_ALGO).apply {
                initVerify(publicKey)
                update(data)
            }
            signature.verify(signatureBytes)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun generateNonce(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun generateTxId(): String {
        return "TXN-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8).uppercase(Locale.US)
    }
}

