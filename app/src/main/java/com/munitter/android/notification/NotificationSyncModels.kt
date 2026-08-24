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
                add(
                    MunitterNotification(
                        id = id,
                        title = item.optString("title", "Munitter通知").ifBlank { "Munitter通知" },
                        message = item.optString("message", "新しい通知があります")
                            .ifBlank { "新しい通知があります" },
                        targetUrl = item.optString("targetUrl").trim(),
                        isRead = item.optBoolean("isRead", true),
                        notificationType = item.optString("notificationType", "notification")
                            .trim().ifBlank { "notification" },
                        actorUserId = actorUserId,
                        actorDisplayName = item.optString("actorName").trim(),
                        actorAvatarUrl = item.optString("actorImageUrl").trim(),
                        actorAvatarVersion = item.optString("actorAvatarVersion").trim(),
                    ),
                )
            }
        }
        val unreadCount = if (root.has("unreadCount")) {
            root.optInt("unreadCount", 0).coerceAtLeast(0)
        } else {
            null
        }
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
