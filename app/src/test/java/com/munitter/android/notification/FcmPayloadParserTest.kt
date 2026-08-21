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
            ),
        )

        assertEquals("user:42", parsed?.notificationId)
        assertEquals("Like", parsed?.notificationType)
        assertEquals(3, parsed?.unreadCount)
        assertEquals(true, parsed?.hasUnreadCount)
        assertEquals("/notifications", parsed?.targetUrl)
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
}
