package com.munitter.android.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FcmPayloadParserTest {
    @Test
    fun parsesCanonicalPayloadAndUnreadCount() {
        val parsed = FcmPayloadParser.parse(
            mapOf(
                "notification_id" to "user:42",
                "notification_type" to "Like",
                "title" to "いいね",
                "body" to "投稿にいいねがつきました",
                "target_url" to "/notifications",
                "unread_count" to "3",
                "timestamp" to "2026-08-21T00:00:00Z",
                "actor_user_id" to "17",
                "actor_display_name" to "送信者A",
                "actor_avatar_url" to "/profile/media/17/avatar?v=avatar-1",
                "actor_avatar_version" to "avatar-1",
            ),
        )

        assertEquals("user:42", parsed?.notificationId)
        assertEquals("Like", parsed?.notificationType)
        assertEquals(3, parsed?.unreadCount)
        assertEquals(true, parsed?.hasUnreadCount)
        assertEquals("/notifications", parsed?.targetUrl)
        assertEquals(17L, parsed?.actorUserId)
        assertEquals("送信者A", parsed?.actorDisplayName)
        assertEquals("/profile/media/17/avatar?v=avatar-1", parsed?.actorAvatarUrl)
        assertEquals("avatar-1", parsed?.actorAvatarVersion)
    }

    @Test
    fun ignoresMissingIdAndUnsafeTarget() {
        assertNull(FcmPayloadParser.parse(mapOf("title" to "missing id")))
        val parsed = FcmPayloadParser.parse(
            mapOf("notification_id" to "dm:1", "target_url" to "https://munitter.com/private"),
        )
        assertEquals("/notifications", parsed?.targetUrl)
        assertEquals(0, parsed?.unreadCount)
        assertEquals(false, parsed?.hasUnreadCount)
    }

    @Test
    fun supportsCamelCaseAndNeverReturnsNegativeUnreadCount() {
        val parsed = FcmPayloadParser.parse(
            mapOf("notificationId" to "group:7", "unreadCount" to "-4", "targetUrl" to "/groups"),
        )
        assertEquals("group:7", parsed?.notificationId)
        assertEquals(0, parsed?.unreadCount)
        assertEquals(true, parsed?.hasUnreadCount)
        assertEquals("/groups", parsed?.targetUrl)
    }

    @Test
    fun rejectsExternalOrTraversalAvatarPathsWithoutCrashing() {
        val parsed = FcmPayloadParser.parse(
            mapOf(
                "notification_id" to "user:17",
                "actor_user_id" to "17",
                "actor_avatar_url" to "https://munitter.com/profile/media/17/avatar",
            ),
        )

        assertEquals("", parsed?.actorAvatarUrl)
        assertEquals(17L, parsed?.actorUserId)
    }

    @Test
    fun sensitivePayloadSuppressesBodyAndEveryRichPreviewInput() {
        val parsed = FcmPayloadParser.parse(
            mapOf(
                "notification_id" to "dm:91",
                "notification_type" to "NewMessage",
                "title" to "secret title",
                "body" to "secret caption and filename.jpg",
                "sensitive_media" to "true",
                "media_preview_allowed" to "false",
                "actor_user_id" to "17",
                "actor_display_name" to "送信者A",
                "actor_avatar_url" to "/profile/media/17/avatar?v=avatar-1",
                "actor_avatar_version" to "avatar-1",
            ),
        )

        assertEquals(true, parsed?.sensitiveMedia)
        assertEquals(false, parsed?.mediaPreviewAllowed)
        assertEquals("新しいDM", parsed?.title)
        assertEquals("センシティブなメディアを受信しました", parsed?.body)
        assertNull(parsed?.actorUserId)
        assertEquals("", parsed?.actorDisplayName)
        assertEquals("", parsed?.actorAvatarUrl)
        assertEquals("", parsed?.actorAvatarVersion)
    }

    @Test
    fun explicitPreviewDenialFailsClosedWithoutSensitiveFlag() {
        val parsed = FcmPayloadParser.parse(
            mapOf(
                "notification_id" to "post:42",
                "notification_type" to "Muni",
                "body" to "generated OCR text",
                "media_preview_allowed" to "false",
            ),
        )

        assertEquals(true, parsed?.sensitiveMedia)
        assertEquals("センシティブなメディアを含む通知があります", parsed?.body)
    }

    @Test
    fun malformedProtectionFlagsFailClosed() {
        val parsed = FcmPayloadParser.parse(
            mapOf(
                "notification_id" to "dm:92",
                "notification_type" to "dm",
                "body" to "must not escape",
                "sensitive_media" to "unknown",
            ),
        )

        assertEquals(true, parsed?.sensitiveMedia)
        assertEquals(false, parsed?.mediaPreviewAllowed)
        assertEquals("センシティブなメディアを受信しました", parsed?.body)
    }
}
