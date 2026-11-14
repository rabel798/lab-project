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
}

