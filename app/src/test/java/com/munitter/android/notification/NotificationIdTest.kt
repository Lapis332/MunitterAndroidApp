package com.munitter.android.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationIdTest {
    @Test
    fun `notification IDs are stable positive and distinguish normal inputs`() {
        assertEquals(NotificationId.from("user-12"), NotificationId.from("user-12"))
        assertTrue(NotificationId.from("user-12") > 0)
        assertTrue(NotificationId.from("user-12") != NotificationId.from("user-13"))
    }
}
