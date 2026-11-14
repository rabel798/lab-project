package com.example.empay

import com.example.empay.bluetooth.AckPacket
import com.example.empay.bluetooth.PaymentPacket
import com.example.empay.crypto.CryptoManager
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 5 P2P Bluetooth Protocol & Packet Serialization Unit Tests.
 * Ensures JSON serialization, deserialization, and payload-to-canonical mappings
 * are byte-accurate between Android devices.
 */
class PaymentPacketsUnitTest {

    private val gson = Gson()

    @Test
    fun testPaymentPacketJsonSerializationAndDeserialization() {
        val packet = PaymentPacket(
            txId = "TXN-TEST-001",
            senderId = "USER_RAHUL",
            senderName = "Rahul Sharma",
            receiverId = "MERCHANT_APEX",
            receiverName = "Apex City Care Pharmacy",
            amount = 12.50,
            timestamp = 1710000000000L,
            nonce = "deadbeefcafebabe0123456789abcdef",
            senderPublicKey = "MHYwEAYHKoZIzj0CAQYFK4EEACIDYgAE...",
            signature = "MEUCIQD...",
            note = "Emergency Paracetamol"
        )

        val jsonString = gson.toJson(packet)
        assertTrue("Serialized JSON must contain txId", jsonString.contains("TXN-TEST-001"))
        assertTrue("Serialized JSON must contain amount", jsonString.contains("12.5"))

        val deserialized = gson.fromJson(jsonString, PaymentPacket::class.java)
        assertEquals(packet.txId, deserialized.txId)
        assertEquals(packet.senderId, deserialized.senderId)
        assertEquals(packet.senderName, deserialized.senderName)
        assertEquals(packet.receiverId, deserialized.receiverId)
        assertEquals(packet.receiverName, deserialized.receiverName)
        assertEquals(packet.amount, deserialized.amount, 0.001)
        assertEquals(packet.timestamp, deserialized.timestamp)
        assertEquals(packet.nonce, deserialized.nonce)
        assertEquals(packet.senderPublicKey, deserialized.senderPublicKey)
        assertEquals(packet.signature, deserialized.signature)
        assertEquals(packet.note, deserialized.note)
    }

    @Test
    fun testAckPacketJsonSerialization() {
        val ack = AckPacket(
            txId = "TXN-TEST-001",
            status = "SUCCESS",
            receiverId = "MERCHANT_APEX",
            receiverName = "Apex City Care Pharmacy",
            timestamp = 1710000001000L,
            ackNonce = "fedcba9876543210deadbeefcafebabe",
            signature = "MEQCIA...",
            message = "Offline payment accepted by Apex Pharmacy"
        )

        val jsonString = gson.toJson(ack)
        val deserialized = gson.fromJson(jsonString, AckPacket::class.java)

        assertEquals("TXN-TEST-001", deserialized.txId)
        assertEquals("SUCCESS", deserialized.status)
        assertEquals("MERCHANT_APEX", deserialized.receiverId)
        assertEquals("Apex City Care Pharmacy", deserialized.receiverName)
        assertEquals("Offline payment accepted by Apex Pharmacy", deserialized.message)
    }

    @Test
    fun testPaymentPacketCanonicalBytesIntegration() {
        val packet = PaymentPacket(
            txId = "TXN-INT-001",
            senderId = "USER_1",
            senderName = "Rahul",
            receiverId = "MERCHANT_1",
            receiverName = "Pharmacy",
            amount = 7.50,
            timestamp = 1710000000L,
            nonce = "a1b2c3d4e5f60718293a4b5c6d7e8f90",
            senderPublicKey = "",
            signature = ""
        )

        val canonicalBytes = CryptoManager.getCanonicalBytes(
            txId = packet.txId,
            senderId = packet.senderId,
            receiverId = packet.receiverId,
            amount = packet.amount,
            timestamp = packet.timestamp,
            nonce = packet.nonce
        )

        val signature = CryptoManager.signData(canonicalBytes)
        val pubKey = CryptoManager.getPublicKeyBase64()

        // Attach signed data to packet
        val signedPacket = packet.copy(senderPublicKey = pubKey, signature = signature)

        // Verifier reconstructs canonical bytes from packet fields
        val reconstructedBytes = CryptoManager.getCanonicalBytes(
            txId = signedPacket.txId,
            senderId = signedPacket.senderId,
            receiverId = signedPacket.receiverId,
            amount = signedPacket.amount,
            timestamp = signedPacket.timestamp,
            nonce = signedPacket.nonce
        )

        val isValid = CryptoManager.verifySignature(signedPacket.senderPublicKey, reconstructedBytes, signedPacket.signature)
        assertTrue("Packet fields reconstructed to canonical bytes must verify peer signature", isValid)
    }
}
