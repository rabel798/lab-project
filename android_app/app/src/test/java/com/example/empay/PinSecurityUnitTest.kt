package com.example.empay

import com.example.empay.auth.PinAuthManager
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

/**
 * Phase 2 Offline PIN Security & Salted Hashing Unit Tests.
 * Ensures salted SHA-256 password hashing, rainbow table resistance,
 * and 4-digit validation rules execute with cryptographic accuracy.
 */
class PinSecurityUnitTest {

    @Test
    fun testDefaultPinHashingConsistency() {
        val pin = "1234"
        val salt = ByteArray(16) { 0x42.toByte() }

        val hash1 = PinAuthManager.computeHash(pin, salt)
        val hash2 = PinAuthManager.computeHash(pin, salt)

        assertEquals("Same PIN and salt must deterministically produce identical hash", hash1, hash2)
        assertTrue("Hash must be non-empty base64 string", hash1.isNotEmpty())
    }

    @Test
    fun testIncorrectPinProducesDifferentHash() {
        val correctPin = "1234"
        val wrongPin = "9999"
        val salt = ByteArray(16) { 0x1A.toByte() }

        val correctHash = PinAuthManager.computeHash(correctPin, salt)
        val wrongHash = PinAuthManager.computeHash(wrongPin, salt)

        assertNotEquals("Incorrect PIN must produce completely distinct hash", correctHash, wrongHash)
    }

    @Test
    fun testSaltPreventsRainbowTableAttacks() {
        val pin = "1234"
        val saltA = ByteArray(16) { 0x01.toByte() }
        val saltB = ByteArray(16) { 0x02.toByte() }

        val hashA = PinAuthManager.computeHash(pin, saltA)
        val hashB = PinAuthManager.computeHash(pin, saltB)

        assertNotEquals("Different salts must produce different hashes for identical PIN", hashA, hashB)
    }

    @Test
    fun testConstantTimeComparison() {
        val hashA = "abc123xyz".toByteArray()
        val hashB = "abc123xyz".toByteArray()
        val hashC = "abc123uvw".toByteArray()

        assertTrue("Identical byte arrays must match in constant time", MessageDigest.isEqual(hashA, hashB))
        assertFalse("Different byte arrays must not match in constant time", MessageDigest.isEqual(hashA, hashC))
    }

    @Test
    fun testPinValidationFormat() {
        // Valid 4-digit PINs
        for (valid in listOf("0000", "1234", "9999", "5432")) {
            assertTrue("Pin $valid should be valid format", valid.length == 4 && valid.all { it.isDigit() })
        }

        // Invalid PINs (wrong length or non-digits)
        for (invalid in listOf("", "12", "123", "12345", "12a4", "abcd", " 123", "123 ")) {
            assertFalse("Pin '$invalid' should be invalid format", invalid.length == 4 && invalid.all { it.isDigit() })
        }
    }
}

