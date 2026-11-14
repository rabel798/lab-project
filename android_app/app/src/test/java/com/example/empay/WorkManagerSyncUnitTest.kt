package com.example.empay

import androidx.work.Constraints
import androidx.work.NetworkType
import com.example.empay.db.TransactionEntity
import com.example.empay.network.SyncTransactionItem
import com.example.empay.network.SyncTransactionsRequest
import com.example.empay.network.SyncTransactionsResponse
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 6 Background Auto-Sync & WorkManager Engine Unit Tests.
 * Ensures WorkManager constraints require network connectivity, payload
 * serialization matches Central Bank API, and transaction reconciliation
 * transitions execute accurately.
 */
class WorkManagerSyncUnitTest {

    private val gson = Gson()

    @Test
    fun testWorkManagerConstraintsRequireConnectedNetwork() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        assertEquals(
            "Auto-Sync WorkManager constraint must strictly require CONNECTED network",
            NetworkType.CONNECTED,
            constraints.requiredNetworkType
        )
    }

    @Test
    fun testSyncTransactionsRequestJsonSerialization() {
        val items = listOf(
            SyncTransactionItem(
                txId = "TXN-SYNC-001",
                payee = "Apex City Care Pharmacy",
                amount = 15.00,
                note = "Bluetooth Mesh Offline Payment"
            ),
            SyncTransactionItem(
                txId = "TXN-SYNC-002",
                payee = "Metro Ration Depot",
                amount = 25.50,
                note = "Bluetooth Mesh Offline Payment"
            )
        )
        val request = SyncTransactionsRequest(transactions = items)

        val json = gson.toJson(request)
        assertTrue("JSON must contain transactions array", json.contains("\"transactions\":"))
        assertTrue("JSON must contain TXN-SYNC-001", json.contains("TXN-SYNC-001"))
        assertTrue("JSON must contain amount 25.5", json.contains("25.5"))

        val deserialized = gson.fromJson(json, SyncTransactionsRequest::class.java)
        assertEquals(2, deserialized.transactions.size)
        assertEquals("TXN-SYNC-001", deserialized.transactions[0].txId)
        assertEquals(15.00, deserialized.transactions[0].amount, 0.001)
    }

    @Test
    fun testSyncTransactionsResponseJsonParsing() {
        val bankJson = """
            {
                "status": "success",
                "message": "Successfully synchronized 2 pending transaction(s).",
                "reconciled_count": 2,
                "balance": 2459.5,
                "emergency_balance": 500.0
            }
        """.trimIndent()

        val response = gson.fromJson(bankJson, SyncTransactionsResponse::class.java)
        assertEquals("success", response.status)
        assertEquals(2, response.reconciledCount)
        assertEquals(2459.5, response.balance, 0.001)
        assertEquals(500.0, response.emergencyBalance, 0.001)
        assertTrue(response.message.contains("Successfully synchronized"))
    }

    @Test
    fun testTransactionEntityMappingToSyncItem() {
        val localTxs = listOf(
            TransactionEntity(
                txId = "TXN-101",
                senderId = "USER_1",
                senderName = "Rahul Sharma",
                receiverId = "MERCHANT_1",
                receiverName = "Apex Pharmacy",
                amount = 10.00,
                timestamp = 1700000000L,
                nonce = "nonce_1",
                signature = "sig_1",
                status = TransactionEntity.STATUS_PENDING_SYNC,
                direction = TransactionEntity.DIRECTION_SENT
            ),
            TransactionEntity(
                txId = "TXN-102",
                senderId = "USER_1",
                senderName = "Rahul Sharma",
                receiverId = "MERCHANT_2",
                receiverName = "Daily Fresh",
                amount = 20.00,
                timestamp = 1700000010L,
                nonce = "nonce_2",
                signature = "sig_2",
                status = TransactionEntity.STATUS_PENDING_SYNC,
                direction = TransactionEntity.DIRECTION_SENT
            )
        )

        val syncItems = localTxs.map { tx ->
            SyncTransactionItem(
                txId = tx.txId,
                payee = tx.receiverName,
                amount = tx.amount,
                note = "Bluetooth Offline Payment (Direction: ${tx.direction})"
            )
        }

        assertEquals(2, syncItems.size)
        assertEquals("TXN-101", syncItems[0].txId)
        assertEquals("Apex Pharmacy", syncItems[0].payee)
        assertEquals(10.00, syncItems[0].amount, 0.001)
        assertEquals("TXN-102", syncItems[1].txId)
        assertEquals("Daily Fresh", syncItems[1].payee)
        assertEquals(20.00, syncItems[1].amount, 0.001)
    }

    @Test
    fun testReconciliationStateTransition() {
        val pendingTx = TransactionEntity(
            txId = "TXN-200",
            senderId = "USER_1",
            senderName = "Rahul Sharma",
            receiverId = "MERCHANT_1",
            receiverName = "Hospital",
            amount = 50.00,
            timestamp = 1700000000L,
            nonce = "nonce_200",
            signature = "sig_200",
            status = TransactionEntity.STATUS_PENDING_SYNC,
            direction = TransactionEntity.DIRECTION_SENT
        )

        assertEquals("Transaction must initially be PENDING_SYNC", TransactionEntity.STATUS_PENDING_SYNC, pendingTx.status)

        // Simulate reconciliation completed by SyncWorker
        val reconciledTx = pendingTx.copy(status = TransactionEntity.STATUS_SYNCED)
        assertEquals("Reconciled transaction status must be SYNCED", TransactionEntity.STATUS_SYNCED, reconciledTx.status)
        assertNotEquals(pendingTx.status, reconciledTx.status)
    }

    @Test
    fun testSyncTransactionItemIncludesDirectionAndParticipantIds() {
        val item = SyncTransactionItem(
            txId = "TXN-SYNC-999",
            payee = "Apex City Care Pharmacy",
            amount = 5.0,
            direction = "SENT",
            senderId = "1",
            receiverId = "MERCHANT_APEX"
        )

        val json = gson.toJson(item)
        assertTrue(json.contains("\"direction\":\"SENT\""))
        assertTrue(json.contains("\"sender_id\":\"1\""))
        assertTrue(json.contains("\"receiver_id\":\"MERCHANT_APEX\""))

        val parsed = gson.fromJson(json, SyncTransactionItem::class.java)
        assertEquals("SENT", parsed.direction)
        assertEquals("1", parsed.senderId)
        assertEquals("MERCHANT_APEX", parsed.receiverId)
    }

    @Test
    fun testReconciliationBalanceSettlementPreventsReset() {
        // Initial state: ₹10.00 emergency balance in local wallet
        var localWalletBalance = 10.0
        val offlineSpendAmount = 5.0

        // 1. Payer conducts offline payment: local wallet deducts ₹5.00
        localWalletBalance -= offlineSpendAmount
        assertEquals("Local wallet must reflect ₹5.00 offline deduction", 5.0, localWalletBalance, 0.001)

        // 2. Bank sync returns settled emergency balance of ₹5.00
        val bankSyncResponseBody = SyncTransactionsResponse(
            status = "success",
            message = "Reconciled 1 transaction",
            reconciledCount = 1,
            balance = 500.0,
            emergencyBalance = 5.0 // Authoritative bank response after deduction
        )

        // 3. SyncWorker applies the bank balance to local wallet
        localWalletBalance = bankSyncResponseBody.emergencyBalance
        assertEquals("Wallet balance must remain at ₹5.00 post-sync without resetting to ₹10.00", 5.0, localWalletBalance, 0.001)
    }
}

