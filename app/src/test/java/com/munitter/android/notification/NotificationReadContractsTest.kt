package com.munitter.android.notification

import org.junit.Assert.*
import org.junit.Test

class NotificationReadContractsTest {
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
