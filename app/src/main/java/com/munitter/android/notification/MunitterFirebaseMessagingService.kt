package com.munitter.android.notification

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.munitter.android.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MunitterFirebaseMessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        if (!isDevelopment()) return
        val store = FcmTokenStore(this)
        store.save(token)
        Log.i(TAG, "FCM token updated tokenHash=${tokenHash(token)}")
        scope.launch { FcmTokenRegistrar(this@MunitterFirebaseMessagingService).registerIfPossible() }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (!isDevelopment()) return
        Log.i(TAG, "FCM message received dataKeys=${message.data.keys.sorted()}")
        val payload = FcmPayloadParser.parse(message.data)
        if (payload == null) {
            Log.w(TAG, "FCM payload ignored: missing notification id")
            return
        }
        val store = NotificationStateStore(this)
        val activeIds = store.addActiveId(payload.notificationId)
        val notification = MunitterNotification(
            id = payload.notificationId,
            title = payload.title,
            message = payload.body,
            targetUrl = payload.targetUrl,
            isRead = false,
        )
        val unreadCount = if (payload.hasUnreadCount) {
            payload.unreadCount
        } else {
            activeIds.size
        }
        MunitterNotificationCenter(this).show(notification, unreadCount, alert = true)
        MunitterNotificationCenter(this).updateSummary(unreadCount)
        Log.i(
            TAG,
            "FCM notification received id=${payload.notificationId} type=${payload.notificationType} unread=$unreadCount",
        )
    }

    override fun onDestroy() {
        scope.coroutineContext.cancel()
        super.onDestroy()
    }

    private fun isDevelopment(): Boolean =
        BuildConfig.ENVIRONMENT.equals("development", ignoreCase = true)

    private fun tokenHash(token: String): String = token.hashCode().toUInt().toString(16)

    private companion object {
        const val TAG = "MunitterFCM"
    }
}
