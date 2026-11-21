package com.munitter.android.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationUnreadCountTest {
    @Test
    fun `delayed zero push refreshes without overwriting a newer notification badge`() {
        val newer = requireNotNull(
            FcmPayloadParser.parse(
                mapOf(
                    "notification_id" to "dm:newer",
                    "unread_count" to "1",
                    "timestamp" to "2026-11-21T10:00:02Z",
                ),
            ),
        )
        val delayedZero = requireNotNull(
            FcmPayloadParser.parse(
                mapOf(
                    "notification_id" to "dm:older",
                    "unread_count" to "0",
                    "timestamp" to "2026-11-21T10:00:01Z",
                ),
            ),
        )
        val currentCount = requireNotNull(
            NotificationUnreadCount.resolveForPush(
                newer.unreadCount.takeIf { newer.hasUnreadCount },
                1,
            ),
        )
        val delayedUpdate = NotificationUnreadCount.resolveForPush(
            delayedZero.unreadCount.takeIf { delayedZero.hasUnreadCount },
            2,
        )

        assertNull(delayedUpdate)
        assertEquals(1, delayedUpdate ?: currentCount)
    }

    @Test
    fun `fresh HTTP zero remains authoritative after a deferred push refresh`() {
        val page = NotificationPageParser.parse("""{"unreadCount":0,"items":[]}""")

        assertTrue(NotificationUnreadCount.isAuthoritativelyEmpty(page.unreadCount))
        assertEquals(0, NotificationUnreadCount.resolve(page.unreadCount, 3))
    }

    @Test
    fun `positive push count overrides stale local notification IDs`() {
        val payload = requireNotNull(
            FcmPayloadParser.parse(mapOf("notification_id" to "dm:92", "unread_count" to "1")),
        )
        val authoritativeCount = payload.unreadCount.takeIf { payload.hasUnreadCount }

        assertEquals(1, NotificationUnreadCount.resolveForPush(authoritativeCount, 4))
    }

    @Test
    fun `legacy push without a count keeps its active notification fallback`() {
        val payload = requireNotNull(
            FcmPayloadParser.parse(mapOf("notification_id" to "user:17")),
        )
        val authoritativeCount = payload.unreadCount.takeIf { payload.hasUnreadCount }

        assertFalse(NotificationUnreadCount.isAuthoritativelyEmpty(authoritativeCount))
        assertEquals(3, NotificationUnreadCount.resolveForPush(authoritativeCount, 3))
    }

    @Test
    fun `malformed push count cannot request clearing existing notifications`() {
        val payload = requireNotNull(
            FcmPayloadParser.parse(mapOf("notification_id" to "user:17", "unread_count" to "invalid")),
        )
        val authoritativeCount = payload.unreadCount.takeIf { payload.hasUnreadCount }

        assertFalse(NotificationUnreadCount.isAuthoritativelyEmpty(authoritativeCount))
        assertEquals(2, NotificationUnreadCount.resolveForPush(authoritativeCount, 2))
    }

    @Test
    fun `negative push count cannot request clearing existing notifications`() {
        val payload = requireNotNull(
            FcmPayloadParser.parse(mapOf("notification_id" to "dm:91", "unread_count" to "-1")),
        )
        val authoritativeCount = payload.unreadCount.takeIf { payload.hasUnreadCount }

        assertFalse(payload.hasUnreadCount)
        assertFalse(NotificationUnreadCount.isAuthoritativelyEmpty(authoritativeCount))
        assertEquals(3, NotificationUnreadCount.resolveForPush(authoritativeCount, 3))
    }

    @Test
    fun `partial DM read uses server total rather than retained DM IDs`() {
        val page = NotificationPageParser.parse(
            """{"unreadCount":1,"items":[{"id":"user:17","isRead":false}]}""",
        )

        assertFalse(NotificationUnreadCount.isAuthoritativelyEmpty(page.unreadCount))
        assertEquals(1, NotificationUnreadCount.resolve(page.unreadCount, 4))
    }

    @Test
    fun `unread DMs keep the icon count when notification rows are empty`() {
        val page = NotificationPageParser.parse("""{"unreadCount":2,"items":[]}""")

        assertTrue(page.items.isEmpty())
        assertEquals(2, NotificationUnreadCount.resolve(page.unreadCount, 0))
    }

    @Test
    fun `legacy refresh response without a count uses active notification fallback`() {
        val page = NotificationPageParser.parse("""{"items":[]}""")

        assertFalse(NotificationUnreadCount.isAuthoritativelyEmpty(page.unreadCount))
        assertEquals(3, NotificationUnreadCount.resolve(page.unreadCount, 3))
    }
}
