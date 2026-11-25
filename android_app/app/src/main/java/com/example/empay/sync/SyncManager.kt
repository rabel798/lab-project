package com.example.empay.sync

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.empay.auth.SessionManager
import com.example.empay.db.AppDatabase
import com.example.empay.network.BankApiClient
import com.example.empay.network.SyncTransactionItem
import com.example.empay.network.SyncTransactionsRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Manages WorkManager configuration, constraints, and scheduling for post-blackout sync.
 */
object SyncManager {

    const val PERIODIC_WORK_NAME = "EmPayPeriodicAutoSync"
    const val IMMEDIATE_WORK_NAME = "EmPayImmediateSync"

    /**
     * Enqueues persistent periodic background auto-sync that wakes up whenever
     * NetworkType.CONNECTED is satisfied (e.g. WiFi or Mobile Data restored).
     */
    fun scheduleAutoSync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodicRequest: PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicRequest
        )
    }

    /**
     * Triggers an immediate one-time sync task with NetworkType.CONNECTED constraint.
     */
    fun triggerImmediateSync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val oneTimeRequest: OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .addTag(IMMEDIATE_WORK_NAME)
                .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            oneTimeRequest
        )
    }

    /**
     * Returns LiveData to observe the status of the immediate sync worker.
     */
    fun getImmediateSyncWorkInfo(context: Context): LiveData<List<WorkInfo>> {
        return WorkManager.getInstance(context).getWorkInfosForUniqueWorkLiveData(IMMEDIATE_WORK_NAME)
    }

    /**
     * Direct synchronous reconciliation: executes immediate network sync against Central Bank
     * without waiting for WorkManager scheduler queue.
     * Marks reconciled transactions as SYNCED in SQLite and updates session balance.
     * Returns true if sync completed successfully, false otherwise.
     */
    suspend fun syncNow(context: Context): Boolean = withContext(Dispatchers.IO) {
        val appDatabase = AppDatabase.getInstance(context)
        val sessionManager = SessionManager.getInstance(context)
        val transactionDao = appDatabase.transactionDao

        val user = sessionManager.getUser()
        val pendingTxs = transactionDao.getPendingSyncTransactionsForUser(user.id.toString(), user.phone)
        if (pendingTxs.isEmpty()) {
            return@withContext true
        }

        val totalAmount = pendingTxs.sumOf { it.amount }
        val syncItems = pendingTxs.map { tx ->
            val isOnlineTx = tx.isOnline
            val ch = if (isOnlineTx) "UPI" else "Bluetooth"
            val defaultNote = if (isOnlineTx) "UPI Online Payment" else "Bluetooth Offline Payment (Direction: ${tx.direction})"
            SyncTransactionItem(
                txId = tx.txId,
                payee = tx.receiverName,
                amount = tx.amount,
                note = defaultNote,
                direction = tx.direction,
                senderId = tx.senderId,
                receiverId = tx.receiverId,
                channel = ch
            )
        }

        val request = SyncTransactionsRequest(
            transactions = syncItems,
            userId = sessionManager.getUser().id
        )

        try {
            val apiService = BankApiClient.getApiService(context)
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
                    context = context,
                    reconciledCount = pendingTxs.size,
                    totalAmount = totalAmount
                )

                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}

