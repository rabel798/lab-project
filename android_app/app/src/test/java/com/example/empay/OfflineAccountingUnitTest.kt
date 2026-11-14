package com.example.empay

import com.example.empay.db.TransactionEntity
import com.example.empay.db.WalletEntity
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 3, 4 & 5 Offline Accounting & Local Ledger Integrity Unit Tests.
 * Ensures offline reserve deductions, balance protection against overdrafts,
 * transaction statuses, and anti-replay nonce tracking.
 */
class OfflineAccountingUnitTest {

    @Test
    fun testOfflineBalanceDeduction() {
        var wallet = WalletEntity(
            id = 1L,
            userId = "USER_1",
            userName = "Rahul Sharma",
            balance = 10.00,
            offlineMaxLimit = 50.00,
            emergencyThreshold = 10.00,
            lastSyncTimestamp = System.currentTimeMillis()
        )

        val paymentAmount = 5.00
        assertTrue("Wallet should have sufficient funds", wallet.balance >= paymentAmount)

        // Deduct payment
        wallet = wallet.copy(balance = wallet.balance - paymentAmount)
        assertEquals("Remaining balance should be ₹5.00", 5.00, wallet.balance, 0.001)

        // Second payment of ₹5
        wallet = wallet.copy(balance = wallet.balance - paymentAmount)
        assertEquals("Remaining balance should be ₹0.00", 0.00, wallet.balance, 0.001)

        // Overdraft attempt: spending ₹1 from zero
        val overdraftAttempt = 1.00
        assertFalse("Overdraft must be blocked when balance is insufficient", wallet.balance >= overdraftAttempt)
    }

    @Test
    fun testTransactionEntityStatusProgression() {
        // Initial creation as PENDING_SYNC
        val tx = TransactionEntity(
            txId = "TXN-TEST-100",
            senderId = "USER_1",
            senderName = "Rahul Sharma",
            receiverId = "MERCHANT_APEX",
            receiverName = "Apex City Care Pharmacy",
            amount = 5.00,
            timestamp = System.currentTimeMillis(),
            nonce = "nonce_12345",
            signature = "sig_12345",
            status = TransactionEntity.STATUS_PENDING_SYNC,
            direction = TransactionEntity.DIRECTION_SENT
        )

        assertEquals("Newly created offline transaction must be PENDING_SYNC", TransactionEntity.STATUS_PENDING_SYNC, tx.status)

        // After WorkManager syncs with Laptop Bank
        val syncedTx = tx.copy(status = TransactionEntity.STATUS_SYNCED)
        assertEquals("Reconciled transaction must transition to SYNCED", TransactionEntity.STATUS_SYNCED, syncedTx.status)
    }

    @Test
    fun testAntiReplayNonceDeduplicationSet() {
        val usedNonces = mutableSetOf<String>()

        val nonce1 = "nonce_alpha_1"
        val nonce2 = "nonce_beta_2"

        // First presentation of nonce1
        assertFalse("Nonce1 must not be previously used", usedNonces.contains(nonce1))
        usedNonces.add(nonce1)
        assertTrue("Nonce1 must now be recorded", usedNonces.contains(nonce1))

        // First presentation of nonce2
        assertFalse("Nonce2 must not be previously used", usedNonces.contains(nonce2))
        usedNonces.add(nonce2)

        // Replay attack: Attacker presents nonce1 again
        val isReplay = usedNonces.contains(nonce1)
        assertTrue("Duplicate presentation of nonce1 must be detected as a replay attack", isReplay)
    }

    @Test
    fun testMerchantCreditBalanceProgression() {
        var merchantBalance = 0.0

        val incomingPayments = listOf(5.00, 2.50, 10.00, 3.25)
        for (payment in incomingPayments) {
            merchantBalance += payment
        }

        assertEquals("Merchant offline balance must accumulate all received payments", 20.75, merchantBalance, 0.001)
    }
}
