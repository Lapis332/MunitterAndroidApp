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
    private val avatarLoader by lazy { NotificationAvatarLoader(this) }

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
            notificationType = payload.notificationType,
            actorUserId = payload.actorUserId,
            actorDisplayName = payload.actorDisplayName,
            actorAvatarUrl = payload.actorAvatarUrl,
            actorAvatarVersion = payload.actorAvatarVersion,
        )
        val unreadCount = if (payload.hasUnreadCount) {
            payload.unreadCount
        } else {
            activeIds.size
        }
        val center = MunitterNotificationCenter(this)
        val avatarSpec = avatarLoader.specFor(
            actorUserId = payload.actorUserId,
            relativeUrl = payload.actorAvatarUrl,
            version = payload.actorAvatarVersion,
        )
        val cachedAvatar = avatarSpec?.let(avatarLoader::loadCached)
        center.show(notification, unreadCount, alert = true, actorAvatar = cachedAvatar)
        center.updateSummary(unreadCount)
        Log.i(
            TAG,
            "FCM notification displayed id=${payload.notificationId} type=${payload.notificationType} unread=$unreadCount avatar=${if (cachedAvatar != null) "cache" else "fallback"}",
        )
        if (avatarSpec != null && cachedAvatar == null) {
            // FirebaseMessagingService may be destroyed as soon as this
            // callback returns. Keep the initial notification immediate, but
            // finish the bounded avatar update before returning so the same
            // notification ID is reliably updated instead of losing the job.
            runBlocking {
                val fetchedAvatar = runCatching { avatarLoader.loadOrFetch(avatarSpec) }
                    .onFailure { exception ->
                        Log.w(TAG, "FCM notification avatar update failed actorId=${payload.actorUserId}", exception)
                    }
                    .getOrNull()
                if (fetchedAvatar != null) {
                    center.show(
                        notification,
                        unreadCount,
                        alert = false,
                        actorAvatar = fetchedAvatar,
                    )
                    Log.i(
                        TAG,
                        "FCM notification updated id=${payload.notificationId} actorId=${payload.actorUserId} avatar=network",
                    )
                }
            }
        }
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
