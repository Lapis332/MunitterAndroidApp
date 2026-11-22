package com.munitter.android.notification

import org.junit.Assert.*
import org.junit.Test

class NotificationSessionFenceTest {
    @Test
    fun `pagination retains original session and rejects response after account cookie changes`() {
        var current = "fixture-session-a"
        val fence = NotificationSessionFence { current }
        val request = fence.capture()
        current = "fixture-session-b"

        assertEquals("fixture-session-a", request.cookie)
        assertFalse(fence.isCurrent(request))
        assertTrue(fence.isCurrent(fence.capture()))
    }

    @Test
    fun `late unauthorized response from signed-out state cannot clear a newly signed-in session`() {
        var current: String? = null
        val fence = NotificationSessionFence { current }
        val request = fence.capture()
        current = "fixture-session-a"

        assertFalse(fence.isCurrent(request))
    }

    @Test
    fun `logout invalidates an in-flight authenticated response`() {
        var current: String? = "fixture-session-a"
        val fence = NotificationSessionFence { current }
        val request = fence.capture()
        current = null

        assertFalse(fence.isCurrent(request))
    }
}
