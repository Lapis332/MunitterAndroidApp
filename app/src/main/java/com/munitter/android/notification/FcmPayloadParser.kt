package com.munitter.android.notification

data class MunitterPushMessage(
    val notificationId: String,
    val notificationType: String,
    val title: String,
    val body: String,
    val targetUrl: String,
    val unreadCount: Int,
    val hasUnreadCount: Boolean,
    val timestamp: String,
    val actorUserId: Long? = null,
    val actorDisplayName: String = "",
    val actorAvatarUrl: String = "",
    val actorAvatarVersion: String = "",
    val sensitiveMedia: Boolean = false,
    val mediaPreviewAllowed: Boolean = true,
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
        val sensitiveMedia = SensitiveNotificationPolicy.isProtected(
            sensitiveMedia = data["sensitive_media"] ?: data["sensitiveMedia"],
            mediaPreviewAllowed = data["media_preview_allowed"]
                ?: data["mediaPreviewAllowed"],
        )
        val mediaPreviewAllowed = !sensitiveMedia
        val receivedTitle = data["title"]?.trim().orEmpty().ifBlank { "Munitter通知" }
        val receivedBody = data["body"]?.trim().orEmpty()
            .ifBlank { data["message"]?.trim().orEmpty() }
            .ifBlank { "新しい通知があります" }
        val title = SensitiveNotificationPolicy.safeTitle(type, receivedTitle, sensitiveMedia)
        val body = SensitiveNotificationPolicy.safeBody(type, receivedBody, sensitiveMedia)
        val targetUrl = data["target_url"]?.trim().orEmpty()
            .ifBlank { data["targetUrl"]?.trim().orEmpty() }
            .let { url -> if (url.startsWith("/")) url else "/notifications" }
        val unreadRaw = data["unread_count"] ?: data["unreadCount"]
        val unreadValue = unreadRaw?.toIntOrNull()
        val unreadCount = unreadValue?.coerceAtLeast(0) ?: 0
        val actorUserId = (data["actor_user_id"] ?: data["actorUserId"])
            ?.trim()
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?.takeUnless { sensitiveMedia }
        val actorAvatarUrl = (data["actor_avatar_url"] ?: data["actorAvatarUrl"])
            ?.trim()
            ?.takeIf(::isSafeAvatarPath)
            ?.takeUnless { sensitiveMedia }
            .orEmpty()
        val actorAvatarVersion = (data["actor_avatar_version"] ?: data["actorAvatarVersion"])
            ?.trim()
            ?.takeIf(::isSafeVersion)
            ?.takeUnless { sensitiveMedia }
            .orEmpty()

        return MunitterPushMessage(
            notificationId = notificationId,
            notificationType = type,
            title = title,
            body = body,
            targetUrl = targetUrl,
            unreadCount = unreadCount,
            hasUnreadCount = unreadValue != null,
            timestamp = data["timestamp"]?.trim().orEmpty(),
            actorUserId = actorUserId,
            actorDisplayName = (data["actor_display_name"] ?: data["actorDisplayName"])
                ?.trim()
                ?.take(MAX_ACTOR_DISPLAY_NAME_LENGTH)
                ?.takeUnless { sensitiveMedia }
                .orEmpty(),
            actorAvatarUrl = actorAvatarUrl,
            actorAvatarVersion = actorAvatarVersion,
            sensitiveMedia = sensitiveMedia,
            mediaPreviewAllowed = mediaPreviewAllowed,
        )
    }

    private fun isSafeAvatarPath(value: String): Boolean {
        if (!value.startsWith("/profile/media/") || value.contains("://") || value.contains('\\')) {
            return false
        }
        return value.split('/', '?', '#').none { it == "." || it == ".." }
    }

    private fun isSafeVersion(value: String): Boolean =
        value.isNotEmpty() && value.length <= MAX_AVATAR_VERSION_LENGTH &&
            value.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }

    private const val MAX_ACTOR_DISPLAY_NAME_LENGTH = 80
    private const val MAX_AVATAR_VERSION_LENGTH = 128
}

object SensitiveNotificationPolicy {
    fun isProtected(sensitiveMedia: String?, mediaPreviewAllowed: String?): Boolean {
        val sensitive = sensitiveMedia.normalizedBoolean()
        val previewAllowed = mediaPreviewAllowed.normalizedBoolean()
        val malformedSensitive = sensitiveMedia != null && sensitive == null
        val malformedPreview = mediaPreviewAllowed != null && previewAllowed == null
        return malformedSensitive || malformedPreview || sensitive == true || previewAllowed == false
    }

    fun safeTitle(type: String, received: String, sensitiveMedia: Boolean): String =
        if (sensitiveMedia) {
            if (isDirectMessage(type)) "新しいDM" else "Munitter通知"
        } else {
            received
        }

    fun safeBody(type: String, received: String, sensitiveMedia: Boolean): String =
        if (sensitiveMedia) {
            if (isDirectMessage(type)) {
                "センシティブなメディアを受信しました"
            } else {
                "センシティブなメディアを含む通知があります"
            }
        } else {
            received
        }

    private fun isDirectMessage(type: String): Boolean =
        type.equals("NewMessage", ignoreCase = true) ||
            type.equals("ImageMessage", ignoreCase = true) ||
            type.equals("dm", ignoreCase = true) ||
            type.contains("Dm", ignoreCase = true)

    private fun String?.normalizedBoolean(): Boolean? = when (this?.trim()?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }
}
