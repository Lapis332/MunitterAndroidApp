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
              "unreadCount": 3,
              "items": [
                {"id":"user-12","title":"いいね","message":"新しい通知","targetUrl":"/post/42","isRead":false,"notificationType":"Muni","actorName":"送信者A","actorImageUrl":"/profile/media/17/avatar","actorAvatarVersion":"avatar-1","actorUserBaseId":17},
                {"id":"group-3","title":"既読","message":"古い通知","targetUrl":"","isRead":true}
              ]
            }
            """.trimIndent(),
        )

        assertTrue(page.hasMore)
        assertEquals(3, page.unreadCount)
        assertEquals(2, page.items.size)
        assertEquals("user-12", page.items[0].id)
        assertEquals("/post/42", page.items[0].targetUrl)
        assertFalse(page.items[0].isRead)
        assertEquals("Muni", page.items[0].notificationType)
        assertEquals(17L, page.items[0].actorUserId)
        assertEquals("送信者A", page.items[0].actorDisplayName)
        assertEquals("/profile/media/17/avatar", page.items[0].actorAvatarUrl)
        assertEquals("avatar-1", page.items[0].actorAvatarVersion)
        assertTrue(page.items[1].isRead)
    }

    @Test
    fun `malformed rows without IDs are ignored`() {
        val page = NotificationPageParser.parse(
            "{\"hasMore\":false,\"items\":[{}, {\"id\":\"  \"}]}"
        )

        assertTrue(page.items.isEmpty())
        assertEquals(null, page.unreadCount)
    }

    @Test
    fun `negative unread count is clamped and does not crash`() {
        val page = NotificationPageParser.parse(
            "{\"hasMore\":false,\"unreadCount\":-4,\"items\":[]}"
        )

        assertEquals(0, page.unreadCount)
    }

    @Test
    fun `snake case unread count remains compatible with development responses`() {
        val page = NotificationPageParser.parse(
            "{\"hasMore\":false,\"unread_count\":2,\"items\":[]}"
        )

        assertEquals(2, page.unreadCount)
    }
}
