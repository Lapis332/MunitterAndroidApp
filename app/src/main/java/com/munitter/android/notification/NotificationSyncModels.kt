package com.munitter.android.notification

import org.json.JSONObject

data class MunitterNotification(
    val id: String,
    val title: String,
    val message: String,
    val targetUrl: String,
    val isRead: Boolean,
    val notificationType: String = "notification",
    val actorUserId: Long? = null,
    val actorDisplayName: String = "",
    val actorAvatarUrl: String = "",
    val actorAvatarVersion: String = "",
    val sensitiveMedia: Boolean = false,
    val mediaPreviewAllowed: Boolean = true,
)

data class NotificationPage(
    val items: List<MunitterNotification>,
    val hasMore: Boolean,
    val unreadCount: Int? = null,
)

object NotificationPageParser {
    fun parse(json: String): NotificationPage {
        val root = JSONObject(json)
        val jsonItems = root.optJSONArray("items")
        val items = buildList {
            if (jsonItems == null) return@buildList
            for (index in 0 until jsonItems.length()) {
                val item = jsonItems.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                if (id.isEmpty()) continue
                val actorUserId = item.optLong("actorUserBaseId", 0L).takeIf { it > 0L }
                val sensitiveMedia = SensitiveNotificationPolicy.isProtected(
                    sensitiveMedia = item.opt("sensitiveMedia")?.toString(),
                    mediaPreviewAllowed = item.opt("mediaPreviewAllowed")?.toString(),
                )
                val notificationType = item.optString("notificationType", "notification")
                    .trim().ifBlank { "notification" }
                add(
                    MunitterNotification(
                        id = id,
                        title = SensitiveNotificationPolicy.safeTitle(
                            notificationType,
                            item.optString("title", "Munitter通知").ifBlank { "Munitter通知" },
                            sensitiveMedia,
                        ),
                        message = SensitiveNotificationPolicy.safeBody(
                            notificationType,
                            item.optString("message", "新しい通知があります")
                                .ifBlank { "新しい通知があります" },
                            sensitiveMedia,
                        ),
                        targetUrl = item.optString("targetUrl").trim(),
                        isRead = item.optBoolean("isRead", true),
                        notificationType = notificationType,
                        actorUserId = actorUserId.takeUnless { sensitiveMedia },
                        actorDisplayName = item.optString("actorName").trim()
                            .takeUnless { sensitiveMedia }.orEmpty(),
                        actorAvatarUrl = item.optString("actorImageUrl").trim()
                            .takeUnless { sensitiveMedia }.orEmpty(),
                        actorAvatarVersion = item.optString("actorAvatarVersion").trim()
                            .takeUnless { sensitiveMedia }.orEmpty(),
                        sensitiveMedia = sensitiveMedia,
                        mediaPreviewAllowed = !sensitiveMedia,
                    ),
                )
            }
        }
        val unreadCountKey = when {
            root.has("unreadCount") -> "unreadCount"
            root.has("unread_count") -> "unread_count"
            else -> null
        }
        val unreadCount = unreadCountKey?.let { root.optInt(it, 0).coerceAtLeast(0) }
        return NotificationPage(
            items = items,
            hasMore = root.optBoolean("hasMore", false),
            unreadCount = unreadCount,
        )
    }
}

object NotificationId {
    fun from(sourceId: String): Int {
        var hash = 0x811c9dc5.toInt()
        sourceId.forEach { character ->
            hash = (hash xor character.code) * 0x01000193
        }
        return (hash and 0x7fffffff).coerceAtLeast(1)
    }
}
