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
