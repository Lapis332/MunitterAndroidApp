package com.munitter.android.notification

import android.annotation.SuppressLint
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.munitter.android.BuildConfig
import com.munitter.android.MainActivity
import org.json.JSONArray

class MunitterNotificationCenter(context: Context) {
    private val appContext = context.applicationContext
    private val notificationManager = NotificationManagerCompat.from(appContext)
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    init {
        ensureChannel()
    }

    fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    fun permissionWasRequested(): Boolean = preferences.getBoolean(PERMISSION_REQUESTED, false)

    fun markPermissionRequested() {
        preferences.edit().putBoolean(PERMISSION_REQUESTED, true).apply()
    }

    @SuppressLint("MissingPermission")
    fun show(notification: MunitterNotification, unreadCount: Int, alert: Boolean) {
        if (!hasNotificationPermission() || !notificationManager.areNotificationsEnabled()) return

        val contentIntent = PendingIntent.getActivity(
            appContext,
            NotificationId.from(notification.id),
            buildTapIntent(notification),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(com.munitter.android.R.drawable.ic_notification)
            .setContentTitle(notification.title)
            .setContentText(notification.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notification.message))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setGroup(GROUP_KEY)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setOnlyAlertOnce(true)
            .setNumber(unreadCount.coerceAtLeast(1))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        if (!alert) builder.setSilent(true)
        notificationManager.notify(NotificationId.from(notification.id), builder.build())
    }

    @SuppressLint("MissingPermission")
    fun updateSummary(unreadCount: Int) {
        if (!hasNotificationPermission() || !notificationManager.areNotificationsEnabled()) return
        if (unreadCount <= 0) {
            notificationManager.cancel(SUMMARY_ID)
            return
        }

        val contentIntent = PendingIntent.getActivity(
            appContext,
            SUMMARY_ID,
            buildTapIntent(null),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val summary = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(com.munitter.android.R.drawable.ic_notification)
            .setContentTitle("Munitter")
            .setContentText("未読通知 $unreadCount 件")
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setOnlyAlertOnce(true)
            .setNumber(unreadCount)
            .setSilent(true)
            .build()
        notificationManager.notify(SUMMARY_ID, summary)
    }

    fun cancel(notificationId: String) {
        notificationManager.cancel(NotificationId.from(notificationId))
    }

    fun cancelAll() {
        notificationManager.cancelAll()
    }

    private fun buildTapIntent(notification: MunitterNotification?): Intent {
        val target = notification?.targetUrl?.takeIf { it.startsWith("/") }
            ?: "/notifications"
        return Intent(appContext, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(BuildConfig.BASE_URL.trimEnd('/') + target)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NOTIFICATION_ID, notification?.id)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        // Channel badge state is user-owned after creation; the channel is new in
        // this implementation, so do not overwrite a user's later preference.
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Munitter通知",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Munitterの未読通知"
                    setShowBadge(true)
                },
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "munitter_notifications"
        const val EXTRA_NOTIFICATION_ID = "munitter.notification.id"
        private const val GROUP_KEY = "munitter.notification.group"
        private const val SUMMARY_ID = 0x4D554E
        private const val PREFERENCES = "munitter_notifications"
        private const val PERMISSION_REQUESTED = "notification_permission_requested"
        const val TAG = "MunitterNotifications"
    }
}
