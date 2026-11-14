package com.example.empay

import com.example.empay.crypto.CryptoManager
import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * Phase 4 & 5 Cryptographic Engine Unit Tests.
 * Rigorously verifies Hardware ECDSA (secp256r1) signing, canonical serialization,
 * anti-replay nonces, and mathematical tamper resistance.
 */
class CryptoUnitTest {

    @Test
    fun testCanonicalBytesFormat() {
        val txId = "TXN-1700000000-A1B2C3D4"
        val senderId = "USER_1"
        val receiverId = "MERCHANT_APEX"
        val amount = 15.50
        val timestamp = 1700000000000L
        val nonce = "a1b2c3d4e5f60718293a4b5c6d7e8f90"

        val canonicalBytes = CryptoManager.getCanonicalBytes(
            txId = txId,
            senderId = senderId,
            receiverId = receiverId,
            amount = amount,
            timestamp = timestamp,
            nonce = nonce
        )

        val canonicalString = String(canonicalBytes, StandardCharsets.UTF_8)
        val expected = "TXN-1700000000-A1B2C3D4|USER_1|MERCHANT_APEX|15.50|1700000000000|a1b2c3d4e5f60718293a4b5c6d7e8f90"

        assertEquals("Canonical string representation must match strict protocol specification", expected, canonicalString)
    }

    @Test
    fun testKeyPairAndFingerprintGeneration() {
        val keyPair = CryptoManager.getOrCreateKeyPair()
        assertNotNull("KeyPair must not be null", keyPair)
        assertNotNull("Public key must exist", keyPair.public)
        assertNotNull("Private key must exist", keyPair.private)
        assertEquals("Algorithm must be EC", "EC", keyPair.public.algorithm)

        val pubKeyBase64 = CryptoManager.getPublicKeyBase64()
        assertTrue("Base64 public key must be non-empty", pubKeyBase64.isNotEmpty())

        val fingerprint = CryptoManager.getPublicKeyFingerprint()
        assertTrue("Fingerprint must indicate secp256r1 curve", fingerprint.startsWith("secp256r1:"))
    }

    @Test
    fun testEcdsaSignAndVerifySuccess() {
        val payload = "TXN-123|USER_1|MERCHANT_APEX|10.00|1700000000|nonce123".toByteArray(StandardCharsets.UTF_8)
        val signature = CryptoManager.signData(payload)
        val pubKey = CryptoManager.getPublicKeyBase64()

        assertTrue("Signature Base64 must be non-empty", signature.isNotEmpty())

        val isValid = CryptoManager.verifySignature(pubKey, payload, signature)
        assertTrue("Valid ECDSA signature must verify successfully", isValid)
    }

    @Test
    fun testTamperedAmountFailsVerification() {
        val txId = "TXN-999"
        val senderId = "USER_1"
        val receiverId = "MERCHANT_APEX"
        val legitAmount = 5.00
        val tamperedAmount = 500.00  // Malicious attacker altered ₹5 to ₹500
        val timestamp = 1700000000L
        val nonce = "1234567890abcdef1234567890abcdef"

        val legitPayload = CryptoManager.getCanonicalBytes(txId, senderId, receiverId, legitAmount, timestamp, nonce)
        val signature = CryptoManager.signData(legitPayload)
        val pubKey = CryptoManager.getPublicKeyBase64()

        // Verify legitimate payload
        assertTrue("Legitimate payload must pass verification", CryptoManager.verifySignature(pubKey, legitPayload, signature))

        // Attacker presents altered amount with original signature
        val tamperedPayload = CryptoManager.getCanonicalBytes(txId, senderId, receiverId, tamperedAmount, timestamp, nonce)
        val isTamperedValid = CryptoManager.verifySignature(pubKey, tamperedPayload, signature)

        assertFalse("Tampered amount must be rejected by ECDSA cryptographic math", isTamperedValid)
    }

    @Test
    fun testTamperedNonceFailsVerification() {
        val legitPayload = "TXN-1|U1|M1|10.00|1000|original_nonce_123".toByteArray(StandardCharsets.UTF_8)
        val signature = CryptoManager.signData(legitPayload)
        val pubKey = CryptoManager.getPublicKeyBase64()

        val replayedPayload = "TXN-1|U1|M1|10.00|1000|replayed_nonce_456".toByteArray(StandardCharsets.UTF_8)
        val isValid = CryptoManager.verifySignature(pubKey, replayedPayload, signature)

        assertFalse("Altered nonce must fail signature verification", isValid)
    }

    @Test
    fun testTamperedReceiverFailsVerification() {
        val legitPayload = "TXN-1|U1|MERCHANT_PHARMACY|10.00|1000|nonce".toByteArray(StandardCharsets.UTF_8)
        val signature = CryptoManager.signData(legitPayload)
        val pubKey = CryptoManager.getPublicKeyBase64()

        val redirectedPayload = "TXN-1|U1|ATTACKER_POCKET|10.00|1000|nonce".toByteArray(StandardCharsets.UTF_8)
        val isValid = CryptoManager.verifySignature(pubKey, redirectedPayload, signature)

        assertFalse("Redirected payment destination must fail signature verification", isValid)
    }

    @Test
    fun testNonceUniquenessAndEntropy() {
        val nonces = (1..100).map { CryptoManager.generateNonce() }

        // All nonces must be 32 characters hex (16 random bytes)
        for (nonce in nonces) {
            assertEquals("Nonce must be 32 hex chars (16 bytes)", 32, nonce.length)
            assertTrue("Nonce must be valid hexadecimal", nonce.all { it.isDigit() || it in 'a'..'f' })
        }

        // All 100 nonces must be distinct (high cryptographic entropy)
        val uniqueCount = nonces.distinct().size
        assertEquals("All generated nonces must be unique across successive transactions", 100, uniqueCount)
    }

    @Test
    fun testTxIdFormat() {
        val txId = CryptoManager.generateTxId()
        assertTrue("TxID must start with TXN- prefix", txId.startsWith("TXN-"))
        val parts = txId.split("-")
        assertEquals("TxID must contain 3 segments: prefix, timestamp, and random suffix", 3, parts.size)
        assertTrue("Second segment must be numeric timestamp", parts[1].toLongOrNull() != null)
    }
}

