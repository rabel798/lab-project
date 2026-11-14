package com.example.empay.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.empay.auth.SessionManager
import com.example.empay.db.AppDatabase
import com.example.empay.db.TransactionEntity
import com.example.empay.network.BankApiClient
import com.example.empay.network.SyncTransactionItem
import com.example.empay.network.SyncTransactionsRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android WorkManager CoroutineWorker.
 * Automatically executed by the Android OS when NetworkType.CONNECTED is satisfied.
 * Queries all PENDING_SYNC offline transactions from local SQLite, pushes batch
 * reconciliation to the Laptop Bank, marks rows as SYNCED, and emits a heads-up notification.
 */
class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val appDatabase = AppDatabase.getInstance(applicationContext)
        val sessionManager = SessionManager.getInstance(applicationContext)
        val transactionDao = appDatabase.transactionDao

        val pendingTxs = transactionDao.getPendingSyncTransactions()
        if (pendingTxs.isEmpty()) {
            return@withContext Result.success(workDataOf("reconciled_count" to 0))
        }

        val totalAmount = pendingTxs.sumOf { it.amount }
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

        val request = SyncTransactionsRequest(
            transactions = syncItems,
            userId = sessionManager.getUser().id
        )

        try {
            val apiService = BankApiClient.getApiService(applicationContext)
            val response = apiService.syncTransactions(request)

            if (response.isSuccessful && response.body()?.status == "success") {
                val body = response.body()!!

                // Update local SQLite ledger: Transition PENDING_SYNC -> SYNCED
                for (tx in pendingTxs) {
                    transactionDao.markAsSynced(tx.txId)
                }

                // Update local wallet and session with reconciled bank balance
                sessionManager.updateBalances(body.balance, body.emergencyBalance)
                appDatabase.walletDao.updateBalance(body.emergencyBalance)

                // Dispatch system notification
                SyncNotificationHelper.showSyncSuccessNotification(
                    context = applicationContext,
                    reconciledCount = pendingTxs.size,
                    totalAmount = totalAmount
                )

                return@withContext Result.success(
                    workDataOf(
                        "reconciled_count" to pendingTxs.size,
                        "total_amount" to totalAmount,
                        "message" to body.message
                    )
                )
            } else {
                if (runAttemptCount < 3) {
                    return@withContext Result.retry()
                } else {
                    return@withContext Result.failure(
                        workDataOf("error" to "Bank returned HTTP ${response.code()}: ${response.errorBody()?.string()}")
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            if (runAttemptCount < 3) {
                return@withContext Result.retry()
            } else {
                return@withContext Result.failure(
                    workDataOf("error" to (e.localizedMessage ?: e.javaClass.simpleName))
                )
            }
        }
    }
}
