package com.munitter.android.notification

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationStateCodecTest {
    @Test
    fun `upgrade preserves native aliases for cancellation and normalizes comparison sources`() {
        val state = NotificationStateCodec.decode(null, """["user:0","user:1","user-1","dm:19:7"]""")
        assertEquals(setOf("user:0", "user:1", "user-1", "dm:19:7"), state.sourcesById.keys)
        assertEquals(setOf("user-1"), state.sourcesById["user:1"])
    }

    @Test
    fun `process restart retains aggregate source mapping authoritative count and read evidence`() {
        val state = NotificationState(
            mapOf("muni-day" to setOf("user-1", "user-2"), "dm:19:7" to setOf("dm:19:7")),
            5,
            setOf("user-3", "group-4"),
        )
        assertEquals(state, NotificationStateCodec.decode(NotificationStateCodec.encode(state), null))
    }

    @Test
    fun `legacy state has no invented server unread count`() {
        val state = NotificationStateCodec.decode(null, """["user:1"]""")
        assertEquals(null, state.unreadCount)
    }
}
