package com.munitter.android.notification

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.*
import org.junit.Test

/** Synthetic URLConnection only: these tests never contact a provider/server. */
class NotificationReadRepositoryTest {
    private class Stub(url: URL, private val status: Int, body: String,
        private val cookies: List<String> = emptyList(), private val onResponse: () -> Unit = {}) : HttpURLConnection(url) {
        val written = ByteArrayOutputStream()
        private val response = body.toByteArray(Charsets.UTF_8)
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode(): Int { onResponse(); return status }
        override fun getInputStream() = ByteArrayInputStream(response)
        override fun getOutputStream() = written
        override fun getHeaderFields(): Map<String, List<String>> = mapOf("Set-Cookie" to cookies)
    }

    @Test
    fun `dm ack uses existing form handler with bound message recipient peer and csrf`() {
        val cookie = "Session=fixture-account-a"
        val opened = mutableListOf<Stub>()
        val repository = MunitterNotificationRepository({ cookie }) { url ->
            val stub = if (opened.isEmpty()) Stub(url, 200, """{"requestToken":"fixture-csrf"}""",
                listOf("__Host-Munitter.Antiforgery=fixture-cookie; Secure"))
            else Stub(url, 200, """{"success":true,"acknowledgedNotificationIds":["dm:19:7"],"deviceTotalUnreadCount":2}""")
            opened += stub
            stub
        }
        val result = repository.acknowledgeRead(NotificationTapRequest("dm:19:7", 9), repository.captureSession())
            as NotificationReadResult.Acknowledged
        assertEquals(setOf("dm:19:7"), result.value.acknowledgedIds)
        assertEquals(2, result.value.unreadCount)
        assertEquals(2, opened.size)
        val post = opened[1]
        assertEquals("/dm/9", post.url.path)
        assertEquals("handler=MarkRead", post.url.query)
        assertEquals("POST", post.requestMethod)
        assertEquals("application/x-www-form-urlencoded", post.getRequestProperty("Content-Type"))
        assertEquals("fixture-csrf", post.getRequestProperty("X-CSRF-TOKEN"))
        assertEquals("Session=fixture-account-a; __Host-Munitter.Antiforgery=fixture-cookie", post.getRequestProperty("Cookie"))
        assertEquals("latestMessageId=19&notificationId=dm%3A19%3A7", post.written.toString(Charsets.UTF_8.name()))
        assertFalse(post.instanceFollowRedirects)
        assertEquals(cookie, repository.captureSession().cookie)
    }

    @Test
    fun `dm switched recipient response cannot cancel or apply its count`() {
        var opened = 0
        val repository = MunitterNotificationRepository({ "Session=fixture-account" }) { url ->
            opened += 1
            if (opened == 1) Stub(url, 200, """{"requestToken":"fixture-csrf"}""")
            else Stub(url, 200, """{"success":true,"acknowledgedNotificationIds":["dm:19:8"],"deviceTotalUnreadCount":0}""")
        }
        assertEquals(NotificationReadResult.RetryableFailure,
            repository.acknowledgeRead(NotificationTapRequest("dm:19:7", 9), repository.captureSession()))
    }

    @Test
    fun `dm session switch during request rejects old success and count`() {
        var cookie = "Session=fixture-account-a"
        var opened = 0
        val repository = MunitterNotificationRepository({ cookie }) { url ->
            opened += 1
            if (opened == 1) Stub(url, 200, """{"requestToken":"fixture-csrf"}""")
            else Stub(url, 200, """{"success":true,"acknowledgedNotificationIds":["dm:19:7"],"deviceTotalUnreadCount":0}""",
                onResponse = { cookie = "Session=fixture-account-b" })
        }
        assertEquals(NotificationReadResult.RetryableFailure,
            repository.acknowledgeRead(NotificationTapRequest("dm:19:7", 9), repository.captureSession()))
    }

    @Test
    fun `dm identity without verified peer context never opens a connection`() {
        var opened = 0
        val repository = MunitterNotificationRepository({ "Session=fixture-account" }) { url ->
            opened += 1
            Stub(url, 200, "{}")
        }
        for (tap in listOf(NotificationTapRequest("dm:19:7"), NotificationTapRequest("dm:19:7", -1),
            NotificationTapRequest("dm:0:7", 9), NotificationTapRequest("user:19", 9))) {
            assertEquals(NotificationReadResult.RetryableFailure, repository.acknowledgeRead(tap, repository.captureSession()))
        }
        assertEquals(NotificationReadResult.RetryableFailure, repository.acknowledgeRead("dm:19:7", repository.captureSession()))
        assertEquals(0, opened)
    }

    @Test
    fun `legacy dm boundary-only success is not an owned notification acknowledgment`() {
        var opened = 0
        val repository = MunitterNotificationRepository({ "Session=fixture-account" }) { url ->
            opened += 1
            if (opened == 1) Stub(url, 200, """{"requestToken":"fixture-csrf"}""")
            else Stub(url, 200, """{"success":true,"currentUserLastReadMessageId":19,"deviceTotalUnreadCount":0}""")
        }
        assertEquals(NotificationReadResult.RetryableFailure,
            repository.acknowledgeRead(NotificationTapRequest("dm:19:7", 9), repository.captureSession()))
    }

    @Test
    fun `wrong-owner or invisible dm denial unblocks next tap without canceling denied card`() {
        for (denial in listOf(400, 404)) {
            val queue = NotificationTapQueue()
            queue.offer(NotificationTapRequest("dm:19:7", 9))
            queue.offer("user:42")
            var state = NotificationState(mapOf("dm:19:7" to setOf("dm:19:7"), "user-42" to setOf("user-42")), 2)
            val coordinator = NotificationStateCoordinator({ state }, { state = it })
            val canceled = mutableSetOf<String>()
            var opened = 0
            val repository = MunitterNotificationRepository({ "Session=fixture-current-account" }) { url ->
                opened += 1
                when (opened) {
                    1, 3 -> Stub(url, 200, """{"requestToken":"fixture-csrf"}""")
                    2 -> Stub(url, denial, """{"success":true,"acknowledgedNotificationIds":["dm:19:7"],"deviceTotalUnreadCount":0}""")
                    else -> Stub(url, 200, """{"success":true,"acknowledgedNotificationIds":["user:42"],"deviceTotalUnreadCount":1}""")
                }
            }
            val denied = queue.firstRequest()!!
            assertEquals(NotificationReadResult.NotAcknowledged,
                repository.acknowledgeRead(denied, repository.captureSession()))
            assertEquals(setOf("dm:19:7", "user-42"), state.sourcesById.keys)
            assertEquals(2, state.unreadCount)
            assertTrue(canceled.isEmpty())
            // The Activity discards this attempt, not the notification/proof.
            queue.discard(denied.id)
            val next = queue.firstRequest()!!
            assertEquals("user-42", next.id)
            val accepted = repository.acknowledgeRead(next, repository.captureSession()) as NotificationReadResult.Acknowledged
            assertNotNull(coordinator.applyReadAcknowledgment(coordinator.beginSync(), accepted.value.acknowledgedIds,
                accepted.value.unreadCount, { canceled += it.canceledIds }))
            queue.complete(next.id)
            assertEquals(setOf("dm:19:7"), state.sourcesById.keys)
            assertEquals(setOf("user-42"), canceled)
            assertEquals(1, state.unreadCount)
            assertEquals(4, opened)
            queue.offer(denied)
            assertEquals(denied, queue.firstRequest())
        }
    }

    @Test
    fun `rate limiting and server failures keep dm request retryable`() {
        for (status in listOf(429, 500, 503)) {
            var opened = 0
            val repository = MunitterNotificationRepository({ "Session=fixture-account" }) { url ->
                opened += 1
                if (opened == 1) Stub(url, 200, """{"requestToken":"fixture-csrf"}""")
                else Stub(url, status, "")
            }
            assertEquals(NotificationReadResult.RetryableFailure,
                repository.acknowledgeRead(NotificationTapRequest("dm:19:7", 9), repository.captureSession()))
        }
    }

    @Test
    fun `authentication rejection stays unauthorized rather than a definitive read denial`() {
        for (status in listOf(401, 403)) {
            var opened = 0
            val repository = MunitterNotificationRepository({ "Session=fixture-account" }) { url ->
                opened += 1
                if (opened == 1) Stub(url, 200, """{"requestToken":"fixture-csrf"}""")
                else Stub(url, status, "")
            }
            assertEquals(NotificationReadResult.Unauthorized,
                repository.acknowledgeRead(NotificationTapRequest("dm:19:7", 9), repository.captureSession()))
        }
    }

    @Test
    fun `ack posts csrf protected one-id request without persisting cookies`() {
        val cookie = "Session=fixture-account-a"
        val opened = mutableListOf<Stub>()
        val repository = MunitterNotificationRepository({ cookie }) { url ->
            val stub = if (opened.isEmpty()) {
                Stub(url, 200, """{"requestToken":"fixture-csrf"}""",
                    listOf("__Host-Munitter.Antiforgery=fixture-cookie; Secure", "Session=wrong-account; Secure"))
            } else {
                Stub(url, 200, """{"success":true,"markedCount":1,"acknowledgedNotificationIds":["user:42"],"deviceTotalUnreadCount":2}""")
            }
            opened += stub
            stub
        }
        val result = repository.acknowledgeRead("user:42", repository.captureSession()) as NotificationReadResult.Acknowledged
        assertEquals(setOf("user-42"), result.value.acknowledgedIds)
        assertEquals(2, result.value.unreadCount)
        assertEquals(2, opened.size)
        val post = opened[1]
        assertEquals("/Notifications", post.url.path)
        assertEquals("handler=MarkItemRead", post.url.query)
        assertEquals("POST", post.requestMethod)
        assertEquals("fixture-csrf", post.getRequestProperty("X-CSRF-TOKEN"))
        assertEquals("Session=fixture-account-a; __Host-Munitter.Antiforgery=fixture-cookie", post.getRequestProperty("Cookie"))
        assertEquals("""{"notificationIds":["user-42"]}""", post.written.toString(Charsets.UTF_8.name()))
        assertFalse(post.instanceFollowRedirects)
        assertEquals(cookie, repository.captureSession().cookie)
    }

    @Test
    fun `switch during csrf fetch prevents posting under a different account`() {
        var cookie = "Session=fixture-account-a"
        var opened = 0
        val repository = MunitterNotificationRepository({ cookie }) { url ->
            opened += 1
            Stub(url, 200, """{"requestToken":"fixture-csrf"}""", onResponse = { cookie = "Session=fixture-account-b" })
        }
        assertEquals(NotificationReadResult.RetryableFailure,
            repository.acknowledgeRead("user:42", repository.captureSession()))
        assertEquals(1, opened)
    }

    @Test
    fun `switch during post rejects even a successful old-account response`() {
        var cookie = "Session=fixture-account-a"
        var opened = 0
        val repository = MunitterNotificationRepository({ cookie }) { url ->
            opened += 1
            if (opened == 1) Stub(url, 200, """{"requestToken":"fixture-csrf"}""")
            else Stub(url, 200, """{"success":true,"acknowledgedNotificationIds":["user:42"],"deviceTotalUnreadCount":0}""",
                onResponse = { cookie = "Session=fixture-account-b" })
        }
        assertEquals(NotificationReadResult.RetryableFailure,
            repository.acknowledgeRead("user:42", repository.captureSession()))
        assertEquals(2, opened)
    }

    @Test
    fun `unauthorized csrf response does not post or fabricate acknowledgment`() {
        var opened = 0
        val repository = MunitterNotificationRepository({ "Session=fixture-account" }) { url ->
            opened += 1
            Stub(url, 401, "")
        }
        assertEquals(NotificationReadResult.Unauthorized,
            repository.acknowledgeRead("user:42", repository.captureSession()))
        assertEquals(1, opened)
    }

    @Test
    fun `device count comes from existing device ui state not active account fallback`() {
        var path = ""
        val repository = MunitterNotificationRepository({ "Session=fixture-account" }) { url ->
            path = url.path
            Stub(url, 200, """{"deviceTotalUnreadCount":4,"unreadCount":0}""")
        }
        val result = repository.fetchDeviceUnreadCount(repository.captureSession()) as NotificationCountResult.Count
        assertEquals("/api/realtime/ui-state", path)
        assertEquals(4, result.value)
    }
}
