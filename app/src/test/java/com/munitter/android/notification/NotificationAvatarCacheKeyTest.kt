package com.munitter.android.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NotificationAvatarCacheKeyTest {
    @Test
    fun `same actor and avatar version reuse one key`() {
        val first = NotificationAvatarCacheKey.from(
            NotificationAvatarSpec(17L, "/profile/media/17/avatar", "avatar-1"),
        )
        val second = NotificationAvatarCacheKey.from(
            NotificationAvatarSpec(17L, "/profile/media/17/avatar", "avatar-1"),
        )

        assertEquals(first, second)
    }

    @Test
    fun `actor or avatar version change cannot reuse another image`() {
        val actorA = NotificationAvatarCacheKey.from(
            NotificationAvatarSpec(17L, "/profile/media/17/avatar", "avatar-1"),
        )
        val actorB = NotificationAvatarCacheKey.from(
            NotificationAvatarSpec(18L, "/profile/media/18/avatar", "avatar-1"),
        )
        val newAvatar = NotificationAvatarCacheKey.from(
            NotificationAvatarSpec(17L, "/profile/media/17/avatar", "avatar-2"),
        )

        assertNotEquals(actorA, actorB)
        assertNotEquals(actorA, newAvatar)
    }
}
