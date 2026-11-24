package com.munitter.android.notification

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object NotificationSyncScheduler {
    private const val WORK_NAME = "munitter-notification-sync"
    internal val badgeHintWorkPolicy = ExistingWorkPolicy.REPLACE

    fun requestRefresh(context: Context) {
        enqueueRefresh(context, ExistingWorkPolicy.KEEP)
    }

    fun requestBadgeRefresh(context: Context) {
        // Invalidate before enqueue: an old HTTP result must not commit while
        // replacement work is waiting for Android's scheduler.
        NotificationStateStore(context).coordinator.invalidateForRefreshHint()
        enqueueRefresh(context, badgeHintWorkPolicy)
    }

    private fun enqueueRefresh(context: Context, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<NotificationSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "$WORK_NAME-refresh",
            policy,
            request,
        )
    }

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<NotificationSyncWorker>(
            15,
            TimeUnit.MINUTES,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS,
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
