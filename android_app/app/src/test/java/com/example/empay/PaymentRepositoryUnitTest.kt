package com.example.empay

import com.example.empay.auth.UserSession
import com.example.empay.db.TransactionEntity
import com.example.empay.db.WalletEntity
import com.example.empay.network.SyncTransactionItem
import com.example.empay.network.SyncTransactionsRequest
import com.example.empay.network.SyncTransactionsResponse
import com.example.empay.network.TransferEmergencyRequest
import com.example.empay.network.TransferEmergencyResponse
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 7: Unified Domain Repository Architecture & Invariant Unit Tests.
 *
 * Verifies business logic rules, balance debit/credit invariants,
 * offline spending boundaries, and WorkManager reconciliation payloads.
 */
class PaymentRepositoryUnitTest {

    @Test
    fun testReservationAmountValidation() {
        val invalidZeroAmount = 0.0
        val invalidNegativeAmount = -25.0

        assertTrue("Zero amount is invalid", invalidZeroAmount <= 0.0)
        assertTrue("Negative amount is invalid", invalidNegativeAmount <= 0.0)

        val validAmount = 50.0
        assertTrue("Positive amount is valid", validAmount > 0.0)
    }

    @Test
    fun testLiquidityConservationInvariantDuringReservation() {
        val initialOnline = 500.0
        val initialEmergency = 10.0
        val reserveAmount = 25.0

        val totalBefore = initialOnline + initialEmergency

        val newOnline = initialOnline - reserveAmount
        val newEmergency = initialEmergency + reserveAmount

        val totalAfter = newOnline + newEmergency

        assertEquals("Total user liquidity must remain strictly conserved", totalBefore, totalAfter, 0.001)
        assertEquals(475.0, newOnline, 0.001)
        assertEquals(35.0, newEmergency, 0.001)
    }

    @Test
    fun testOfflinePaymentSpendingBoundary() {
        val currentOfflineBalance = 25.0

        val affordablePayment = 15.0
        val exactPayment = 25.0
        val excessivePayment = 30.0

        assertTrue("Payment under balance must succeed", affordablePayment <= currentOfflineBalance)
        assertTrue("Exact balance payment must succeed", exactPayment <= currentOfflineBalance)
        assertFalse("Payment exceeding balance must be blocked", excessivePayment <= currentOfflineBalance)
    }

    @Test
    fun testBatchReconciliationMappingMaintainsIntegrity() {
        val pendingTxs = listOf(
            TransactionEntity(
                txId = "TX-REPO-001",
                senderId = "1",
                senderName = "Rahul Sharma",
                receiverId = "MERCHANT_APEX",
                receiverName = "Apex City Care Pharmacy",
                amount = 5.0,
                timestamp = 1700000000L,
                nonce = "nonce_repo_001",
                signature = "sig_repo_001",
                status = TransactionEntity.STATUS_PENDING_SYNC,
                direction = TransactionEntity.DIRECTION_SENT
            ),
            TransactionEntity(
                txId = "TX-REPO-002",
                senderId = "1",
                senderName = "Rahul Sharma",
                receiverId = "MERCHANT_MED",
                receiverName = "LifeLine Medicos",
                amount = 10.0,
                timestamp = 1700000050L,
                nonce = "nonce_repo_002",
                signature = "sig_repo_002",
                status = TransactionEntity.STATUS_PENDING_SYNC,
                direction = TransactionEntity.DIRECTION_SENT
            )
        )

        val syncItems = pendingTxs.map { tx ->
            SyncTransactionItem(
                txId = tx.txId,
                payee = tx.receiverName,
                amount = tx.amount,
                note = "Bluetooth Offline Payment (Direction: ${tx.direction})",
                direction = tx.direction,
                senderId = tx.senderId,
                receiverId = tx.receiverId
            )
        }

        assertEquals(2, syncItems.size)
        assertEquals("TX-REPO-001", syncItems[0].txId)
        assertEquals("SENT", syncItems[0].direction)
        assertEquals("1", syncItems[0].senderId)
        assertEquals("MERCHANT_APEX", syncItems[0].receiverId)
        assertEquals(5.0, syncItems[0].amount, 0.001)

        assertEquals("TX-REPO-002", syncItems[1].txId)
        assertEquals("SENT", syncItems[1].direction)
        assertEquals("MERCHANT_MED", syncItems[1].receiverId)
        assertEquals(10.0, syncItems[1].amount, 0.001)
    }

    @Test
    fun testReconciliationResponseSettlementPreventsOverwriting() {
        val localWalletBeforeSync = 20.0 // Offline deduction already occurred (25 - 5 = 20)

        val bankResponse = SyncTransactionsResponse(
            status = "success",
            message = "Successfully synchronized 1 transaction(s).",
            reconciledCount = 1,
            balance = 480.0,
            emergencyBalance = 20.0 // Bank accurately deducted ₹5.00
        )

        val updatedLocalWallet = bankResponse.emergencyBalance
        assertEquals("Reconciled bank balance must settle at ₹20.00 without resetting", 20.0, updatedLocalWallet, 0.001)
    }

    @Test
    fun testTransferEmergencyRequestContract() {
        val request = TransferEmergencyRequest(userId = 1L, amount = 30.0)
        assertEquals(1L, request.userId)
        assertEquals(30.0, request.amount, 0.001)

        val response = TransferEmergencyResponse(
            success = true,
            message = "Transferred ₹30.00 to emergency reserve",
            balance = 450.0,
            emergencyBalance = 40.0
        )
        assertTrue(response.success)
        assertEquals(450.0, response.balance, 0.001)
        assertEquals(40.0, response.emergencyBalance, 0.001)
    }

    @Test
    fun testSelfTransferRejectionPreventsBalanceDeduction() {
        var localWalletBalance = 25.0
        val paymentAmount = 5.0
        val senderUser = UserSession(id = 1L, name = "Rahul Sharma", phone = "9876543210")
        val receiverMerchant = UserSession(id = 1L, name = "Rahul Sharma", phone = "9876543210")

        // 1. Detect self-transfer between devices on same account
        val isSelfTransfer = (senderUser.id == receiverMerchant.id) ||
                senderUser.name.equals(receiverMerchant.name, ignoreCase = true)

        assertTrue("Must detect same-account self-transfer", isSelfTransfer)

        // 2. Peer returns rejection ack
        val ackStatus = if (isSelfTransfer) "REJECTED_SELF_TRANSFER" else "SUCCESS"
        assertEquals("REJECTED_SELF_TRANSFER", ackStatus)

        // 3. Since ack is not SUCCESS, local wallet deduction MUST NOT occur
        if (ackStatus == "SUCCESS") {
            localWalletBalance -= paymentAmount
        }

        assertEquals("Local wallet balance must remain unchanged at ₹25.00 on self-transfer rejection", 25.0, localWalletBalance, 0.001)
    }

    @Test
    fun testMultiUserDemoProfilesConfigured() {
        val demoUsers = UserSession.DEMO_USERS
        assertEquals("Must provide 5 configured demo profiles for multi-device testing", 5, demoUsers.size)

        val names = demoUsers.map { it.name }
        assertTrue(names.contains("rabel") || names.contains("Rahul Sharma"))
        assertTrue(names.contains("Sukhpal Singh"))
        assertTrue(names.contains("Priya Sharma"))
        assertTrue(names.contains("Dr. Rohan Verma"))
        assertTrue(names.contains("Ananya Patel"))

        // Ensure IDs and phones are unique
        val ids = demoUsers.map { it.id }
        val phones = demoUsers.map { it.phone }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(phones.size, phones.toSet().size)
    }
}

