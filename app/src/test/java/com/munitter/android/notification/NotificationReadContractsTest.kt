package com.munitter.android.notification

import org.junit.Assert.*
import org.junit.Test

class NotificationReadContractsTest {
    @Test
    fun `dm identities bind positive bounded message and recipient without widening ordinary history`() {
        assertEquals("dm:19:7", NotificationTapPolicy.concreteId("dm:19:7"))
        assertEquals(NotificationDmIdentity(19, 7), NotificationTapPolicy.dmIdentity("dm:19:7"))
        assertNull(NotificationTapPolicy.ordinaryId("dm:19:7"))
        for (id in listOf("dm:0:7", "dm:19:0", "dm:-1:7", "dm:019:7", "dm:19:07", "dm:19",
            "dm:19:7:8", "DM:19:7", "dm:9223372036854775808:7", "dm:19:9223372036854775808", "dm:19:7\n")) {
            assertNull(NotificationTapPolicy.concreteId(id))
        }
    }

    @Test
    fun `dm peer comes only from an exact numeric same-origin detail target`() {
        assertEquals(NotificationTapRequest("dm:19:7", 9), NotificationTapPolicy.requestForTarget(
            "dm:19:7", "https://dev.munitter.com/dm/9", "dev.munitter.com"))
        for (target in listOf("/post/9", "/dm/0", "/dm/-1", "/dm/09", "/dm/2147483648", "/dm/9/",
            "/dm/%39", "/dm/9?peer=10", "/dm/9#other")) {
            assertNull(NotificationTapPolicy.requestForTarget("dm:19:7", "https://dev.munitter.com$target", "dev.munitter.com"))
        }
        assertNull(NotificationTapPolicy.requestForTarget("dm:19:7", "https://munitter.com/dm/9", "dev.munitter.com"))
    }

    @Test
    fun `dm queue retains verified peer context and never manufactures it from an id`() {
        val queue = NotificationTapQueue()
        queue.offer("dm:19:7")
        queue.offer(NotificationTapRequest("dm:19:7"))
        queue.offer(NotificationTapRequest("dm:19:7", 0))
        assertNull(queue.firstRequest())
        queue.offer(NotificationTapRequest("dm:19:7", 9))
        queue.offer(NotificationTapRequest("dm:19:7", 10))
        assertEquals(NotificationTapRequest("dm:19:7", 9), queue.firstRequest())
        queue.discard("dm:19:7")
        queue.offer(NotificationTapRequest("dm:19:7", 10))
        assertEquals(NotificationTapRequest("dm:19:7", 10), queue.firstRequest())
    }

    @Test
    fun `dm read response requires exact recipient message ack and device authority`() {
        val accepted = NotificationReadResponseParser.parse(
            """{"success":true,"acknowledgedNotificationIds":["dm:19:7"],"deviceTotalUnreadCount":2}""", "dm:19:7")!!
        assertEquals(setOf("dm:19:7"), accepted.acknowledgedIds)
        assertEquals(2, accepted.unreadCount)
        for (body in listOf(
            """{"success":true,"currentUserLastReadMessageId":19,"deviceTotalUnreadCount":0}""",
            """{"success":true,"acknowledgedNotificationIds":["dm:20:7"],"deviceTotalUnreadCount":0}""",
            """{"success":true,"acknowledgedNotificationIds":["dm:19:8"],"deviceTotalUnreadCount":0}""",
            """{"success":true,"acknowledgedNotificationIds":["user:19"],"deviceTotalUnreadCount":0}""",
            """{"success":true,"acknowledgedNotificationIds":["dm:19:7"],"unreadCount":0}""")) {
            assertNull(NotificationReadResponseParser.parse(body, "dm:19:7"))
        }
    }

    @Test
    fun `only positive concrete ordinary identities can be acknowledged`() {
        assertEquals("user-42", NotificationTapPolicy.ordinaryId("user:42"))
        assertEquals("group-9", NotificationTapPolicy.ordinaryId("group-9"))
        for (id in listOf("user:0", "group:-1", "user:01", "user:9223372036854775808",
            "dm:42", "grouped-muni-token", "user:42\n", "User:42")) {
            assertNull(NotificationTapPolicy.ordinaryId(id))
        }
    }

    @Test
    fun `tap requires exact current https origin and view action`() {
        assertEquals("user-1", NotificationTapPolicy.fromIntent("android.intent.action.VIEW",
            "https://dev.munitter.com/post/1", "user:1", "dev.munitter.com"))
        for (url in listOf("http://dev.munitter.com/post/1", "https://munitter.com/post/1",
            "https://dev.munitter.com.evil.invalid/post/1", "https://name@dev.munitter.com/post/1",
            "https://dev.munitter.com:444/post/1", "javascript:alert(1)")) {
            assertNull(NotificationTapPolicy.fromIntent("android.intent.action.VIEW", url, "user:1", "dev.munitter.com"))
        }
        assertNull(NotificationTapPolicy.fromIntent("android.intent.action.MAIN",
            "https://dev.munitter.com/", "user:1", "dev.munitter.com"))
    }

    @Test
    fun `cold and warm tap queue deduplicates without acknowledging before success`() {
        val queue = NotificationTapQueue()
        queue.offer("user:1")
        queue.offer("user-1")
        queue.offer("group:2")
        assertEquals("user-1", queue.first())
        assertEquals("user-1", queue.first())
        queue.complete("user-1")
        queue.offer("user:1")
        assertEquals("group-2", queue.first())
        queue.complete("group-2")
        assertEquals("user-1", queue.first())
        queue.complete("user-1")
        assertNull(queue.first())
    }

    @Test
    fun `unacknowledged wrong-account attempt allows a later valid retap`() {
        val queue = NotificationTapQueue()
        queue.offer("user:42")
        queue.discard("user-42")
        queue.offer("user:42")
        assertEquals("user-42", queue.first())
    }

    @Test
    fun `read proof requires explicit owned matching ids rather than marked count`() {
        assertNull(NotificationReadResponseParser.parse("""{"success":true,"markedCount":1,"deviceTotalUnreadCount":0}""", "user:1"))
        assertNull(NotificationReadResponseParser.parse("""{"success":true,"acknowledgedNotificationIds":["user:2"],"deviceTotalUnreadCount":0}""", "user:1"))
        val empty = NotificationReadResponseParser.parse("""{"success":true,"markedCount":0,"acknowledgedNotificationIds":[],"deviceTotalUnreadCount":3}""", "user:1")!!
        assertTrue(empty.acknowledgedIds.isEmpty())
        assertEquals(3, empty.unreadCount)
        val existing = NotificationReadResponseParser.parse("""{"success":true,"markedCount":0,"acknowledgedNotificationIds":["user:1"],"deviceTotalUnreadCount":2}""", "user-1")!!
        assertEquals(setOf("user-1"), existing.acknowledgedIds)
        assertEquals(2, existing.unreadCount)
    }

    @Test
    fun `device authority never falls back to selected account or malformed counts`() {
        assertEquals(5, NotificationDeviceUnreadParser.parse("""{"deviceTotalUnreadCount":5,"unreadCount":0}"""))
        for (body in listOf("""{"unreadCount":0}""", """{"deviceTotalUnreadCount":-1}""",
            """{"deviceTotalUnreadCount":true}""", """{"deviceTotalUnreadCount":1.5}""",
            """{"deviceTotalUnreadCount":2147483648}""")) {
            assertNull(NotificationDeviceUnreadParser.parse(body))
        }
    }

    @Test
    fun `web hint requires main frame current origin and fixed message ignoring counts`() {
        assertTrue(NotificationUnreadHintPolicy.accepts("https://dev.munitter.com", "https://dev.munitter.com/post/1",
            "dev.munitter.com", true, "unread-state-changed:v1"))
        assertFalse(NotificationUnreadHintPolicy.accepts("https://dev.munitter.com", "https://dev.munitter.com/",
            "dev.munitter.com", false, "unread-state-changed:v1"))
        assertFalse(NotificationUnreadHintPolicy.accepts("https://munitter.com", "https://dev.munitter.com/",
            "dev.munitter.com", true, "unread-state-changed:v1"))
        assertFalse(NotificationUnreadHintPolicy.accepts("https://dev.munitter.com", "https://munitter.com/",
            "dev.munitter.com", true, "unread-state-changed:v1"))
        assertFalse(NotificationUnreadHintPolicy.accepts("https://dev.munitter.com", "https://dev.munitter.com/",
            "dev.munitter.com", true, """{"deviceTotalUnreadCount":0}"""))
    }

    @Test
    fun `only csrf cookie is merged ephemerally and authentication cannot be replaced`() {
        val original = "Session=original; __Host-Munitter.Antiforgery=old; Other=retained"
        val merged = NotificationAntiforgeryCookie.merge(original,
            listOf("Session=replaced; Secure", "__Host-Munitter.Antiforgery=new; Path=/; Secure"))
        assertEquals("Session=original; Other=retained; __Host-Munitter.Antiforgery=new", merged)
        assertEquals(original, NotificationAntiforgeryCookie.merge(original, listOf("Session=replaced")))
        assertNull(NotificationAntiforgeryCookie.merge(original,
            listOf("__Host-Munitter.Antiforgery=a", "__Host-Munitter.Antiforgery=b")))
        assertNull(NotificationAntiforgeryCookie.merge(original, listOf("__Host-Munitter.Antiforgery=bad\r\nvalue")))
    }
}
