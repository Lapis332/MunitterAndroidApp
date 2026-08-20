package com.munitter.android.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPageParserTest {
    @Test
    fun `refresh payload keeps unread state and target URL`() {
        val page = NotificationPageParser.parse(
            """
            {
              "hasMore": true,
              "items": [
                {"id":"user-12","title":"いいね","message":"新しい通知","targetUrl":"/post/42","isRead":false},
                {"id":"group-3","title":"既読","message":"古い通知","targetUrl":"","isRead":true}
              ]
            }
            """.trimIndent(),
        )

        assertTrue(page.hasMore)
        assertEquals(2, page.items.size)
        assertEquals("user-12", page.items[0].id)
        assertEquals("/post/42", page.items[0].targetUrl)
        assertFalse(page.items[0].isRead)
        assertTrue(page.items[1].isRead)
    }

    @Test
    fun `malformed rows without IDs are ignored`() {
        val page = NotificationPageParser.parse(
            "{\"hasMore\":false,\"items\":[{}, {\"id\":\"  \"}]}"
        )

        assertTrue(page.items.isEmpty())
    }
}
