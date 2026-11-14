package com.example.empay.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.empay.MainActivity
import com.example.empay.R

/**
 * Handles system notification channels and user notifications for background auto-sync events.
 */
object SyncNotificationHelper {

    const val CHANNEL_ID = "empay_sync_channel"
    private const val CHANNEL_NAME = "EmPay Bank Reconciliation"
    private const val CHANNEL_DESC = "Notifications when offline mesh payments reconcile with Central Bank"
    private const val NOTIFICATION_ID = 2001

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance).apply {
                description = CHANNEL_DESC
                enableVibration(true)
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.createNotificationChannel(channel)
        }
    }

    fun showSyncSuccessNotification(context: Context, reconciledCount: Int, totalAmount: Double) {
        createNotificationChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val formattedAmount = String.format("%.2f", totalAmount)
        val contentText = if (reconciledCount == 1) {
            "Reconciled 1 offline emergency payment (₹$formattedAmount) with Central Bank."
        } else {
            "Reconciled $reconciledCount offline emergency payments (₹$formattedAmount) with Central Bank."
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("EmPay: Offline Ledger Reconciled 🟢")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        try {
            notificationManager?.notify(NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            // Android 13+ without POST_NOTIFICATIONS permission granted yet
            e.printStackTrace()
        }
    }
}

