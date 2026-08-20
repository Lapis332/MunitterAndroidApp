package com.munitter.android.notification

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class NotificationSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = when (NotificationSyncEngine(applicationContext).sync()) {
        NotificationSyncOutcome.Succeeded,
        NotificationSyncOutcome.Unauthorized,
        -> Result.success()
        is NotificationSyncOutcome.Retryable -> Result.retry()
    }
}
