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
import kotlinx.coroutines.runBlocking

class MunitterFirebaseMessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        if (!isSupportedEnvironment()) return
        val store = FcmTokenStore(this)
        store.save(token)
        Log.i(TAG, "FCM token updated tokenHash=${tokenHash(token)}")
        scope.launch { FcmTokenRegistrar(this@MunitterFirebaseMessagingService).registerIfPossible() }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (!isSupportedEnvironment()) return
        Log.i(TAG, "FCM message received dataKeys=${message.data.keys.sorted()}")
        val payload = FcmPayloadParser.parse(message.data)
        if (payload == null) {
            Log.w(TAG, "FCM payload ignored: missing notification id")
            return
        }
        val coordinator = NotificationStateStore(this).coordinator
        val renderer = NotificationRenderer(this, coordinator)
        val notification = MunitterNotification(
            id = payload.notificationId,
            title = payload.title,
            message = payload.body,
            targetUrl = payload.targetUrl,
            isRead = false,
            notificationType = payload.notificationType,
            actorUserId = payload.actorUserId,
            actorDisplayName = payload.actorDisplayName,
            actorAvatarUrl = payload.actorAvatarUrl,
            actorAvatarVersion = payload.actorAvatarVersion,
            sensitiveMedia = payload.sensitiveMedia,
            mediaPreviewAllowed = payload.mediaPreviewAllowed,
        )
        val mutation = coordinator.applyPush(
            notification,
            payload.unreadCount.takeIf { payload.hasUnreadCount },
            renderer::publish,
        )
        // A push is only a hint. Refresh even when its count was nonzero, so a
        // delayed delivery cannot leave a read event or stale count on screen.
        NotificationSyncScheduler.requestRefresh(this)
        if (mutation == null) {
            Log.i(TAG, "FCM notification requested authoritative refresh without rendering")
            return
        }
        Log.i(
            TAG,
            "FCM notification displayed id=${mutation.shown.single().id} type=${payload.notificationType} unread=${mutation.unreadCount}",
        )
        // Keep this bounded avatar job alive until Firebase's callback returns.
        // The shared revision guard prevents a later HTTP read/clear from being
        // undone when the bitmap arrives.
        runBlocking {
            runCatching { renderer.updateAvatars(mutation) }
                .onFailure { Log.w(TAG, "FCM notification avatar update failed", it) }
        }
    }

    override fun onDestroy() {
        scope.coroutineContext.cancel()
        super.onDestroy()
    }

    private fun isSupportedEnvironment(): Boolean =
        FcmRuntimeEnvironment.isSupported(BuildConfig.ENVIRONMENT)

    private fun tokenHash(token: String): String = token.hashCode().toUInt().toString(16)

    private companion object {
        const val TAG = "MunitterFCM"
    }
}
