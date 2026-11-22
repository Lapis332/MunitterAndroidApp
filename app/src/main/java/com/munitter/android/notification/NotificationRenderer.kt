package com.munitter.android.notification

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class NotificationRenderer(
    context: Context,
    private val coordinator: NotificationStateCoordinator,
) {
    private val center = MunitterNotificationCenter(context)
    private val avatars = NotificationAvatarLoader(context)

    // Called inside the shared coordinator transaction. Never fetch network
    // resources here: cancellation and initial rendering must remain atomic.
    fun publish(mutation: NotificationMutation) {
        if (mutation.clearAll) {
            center.cancelAll()
            return
        }
        mutation.canceledIds.forEach(center::cancel)
        var latestAvatar: Bitmap? = null
        mutation.shown.forEach { notification ->
            val avatar = avatarSpec(notification)?.let(avatars::loadCached)
            if (avatar != null) latestAvatar = avatar
            center.show(notification, notification.id in mutation.alertIds, avatar)
        }
        center.updateSummary(mutation.unreadCount, latestAvatar)
    }

    suspend fun updateAvatars(mutation: NotificationMutation) {
        for (notification in mutation.shown) {
            val spec = avatarSpec(notification) ?: continue
            if (avatars.loadCached(spec) != null) continue
            val bitmap = avatars.loadOrFetch(spec) ?: continue
            currentCoroutineContext().ensureActive()
            coordinator.updateAvatar(mutation, notification.id) { count ->
                center.show(notification, alert = false, actorAvatar = bitmap)
                center.updateSummary(count, bitmap)
            }
        }
    }

    private fun avatarSpec(notification: MunitterNotification) =
        if (notification.mediaPreviewAllowed && !notification.sensitiveMedia) {
            avatars.specFor(notification.actorUserId, notification.actorAvatarUrl, notification.actorAvatarVersion)
        } else null
}
