package com.munitter.android.notification

data class MunitterPushMessage(
    val notificationId: String,
    val notificationType: String,
    val title: String,
    val body: String,
    val targetUrl: String,
    val unreadCount: Int,
    val timestamp: String,
)

object FcmPayloadParser {
    fun parse(data: Map<String, String>): MunitterPushMessage? {
        val notificationId = data["notification_id"]?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: data["notificationId"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val type = data["notification_type"]?.trim().orEmpty()
            .ifBlank { data["notificationType"]?.trim().orEmpty() }
            .ifBlank { "notification" }
        val title = data["title"]?.trim().orEmpty().ifBlank { "Munitter通知" }
        val body = data["body"]?.trim().orEmpty()
            .ifBlank { data["message"]?.trim().orEmpty() }
            .ifBlank { "新しい通知があります" }
        val targetUrl = data["target_url"]?.trim().orEmpty()
            .ifBlank { data["targetUrl"]?.trim().orEmpty() }
            .let { url -> if (url.startsWith("/")) url else "/notifications" }
        val unreadCount = data["unread_count"]?.toIntOrNull()?.coerceAtLeast(0)
            ?: data["unreadCount"]?.toIntOrNull()?.coerceAtLeast(0)
            ?: 0

        return MunitterPushMessage(
            notificationId = notificationId,
            notificationType = type,
            title = title,
            body = body,
            targetUrl = targetUrl,
            unreadCount = unreadCount,
            timestamp = data["timestamp"]?.trim().orEmpty(),
        )
    }
}
