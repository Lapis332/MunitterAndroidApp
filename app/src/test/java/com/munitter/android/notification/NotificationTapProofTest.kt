package com.munitter.android.notification

import org.junit.Assert.*
import org.junit.Test

class NotificationTapProofTest {
    private class Fixture {
        var stored: Map<String, NotificationTapProof> = emptyMap()
        var serial = 0
        var writable = true
        fun registry() = NotificationTapProofRegistry("dev.munitter.com", { stored },
            { if (writable) { stored = it; true } else false },
            { "00000000-0000-0000-0000-" + (++serial).toString(16).padStart(12, '0') })
    }

    private val url = "https://dev.munitter.com/post/733"
    private val action = "android.intent.action.VIEW"

    @Test
    fun `same origin and arbitrary exported intent id are not read authority`() {
        val registry = Fixture().registry()
        assertEquals("user-42", NotificationTapPolicy.fromIntent(action, url, "user:42", "dev.munitter.com"))
        assertNull(NotificationTapPolicy.fromVerifiedIntent(action, url, "user:42", null, "dev.munitter.com", registry))
        assertNull(NotificationTapPolicy.fromVerifiedIntent(action, url, "user:42",
            "00000000-0000-0000-0000-000000000001", "dev.munitter.com", registry))
    }

    @Test
    fun `issued capability binds exact id and target and rejects missing or forged proof`() {
        val registry = Fixture().registry()
        val proof = registry.issue("user:42", url)!!
        assertEquals("user-42", NotificationTapPolicy.fromVerifiedIntent(action, url, "user-42", proof,
            "dev.munitter.com", registry))
        assertNull(NotificationTapPolicy.fromVerifiedIntent(action, url, "user:43", proof, "dev.munitter.com", registry))
        assertNull(NotificationTapPolicy.fromVerifiedIntent(action, "$url/other", "user:42", proof, "dev.munitter.com", registry))
        assertNull(NotificationTapPolicy.fromVerifiedIntent(action, url, "user:42", "invalid", "dev.munitter.com", registry))
        assertNull(NotificationTapPolicy.fromVerifiedIntent(action, "https://munitter.com/post/733", "user:42", proof,
            "dev.munitter.com", registry))
    }

    @Test
    fun `persisted card proof survives process reconstruction and stable rerender`() {
        val fixture = Fixture()
        val proof = fixture.registry().issue("user:42", url)!!
        val restarted = fixture.registry()
        assertEquals(proof, restarted.issue("user-42", url))
        assertTrue(restarted.trusts("user:42", url, proof))
        assertEquals(1, fixture.serial)
    }

    @Test
    fun `changed target and cancellation invalidate previous capabilities`() {
        val registry = Fixture().registry()
        val first = registry.issue("user:42", url)!!
        val second = registry.issue("user:42", "$url?new=1")!!
        assertNotEquals(first, second)
        assertFalse(registry.trusts("user:42", url, first))
        registry.remove("user-42")
        assertFalse(registry.trusts("user:42", "$url?new=1", second))
        val third = registry.issue("group:2", url)!!
        registry.clear()
        assertFalse(registry.trusts("group:2", url, third))
    }

    @Test
    fun `failed private state write cannot issue a usable capability`() {
        val fixture = Fixture()
        fixture.writable = false
        assertNull(fixture.registry().issue("user:42", url))
        assertTrue(fixture.stored.isEmpty())
    }

    @Test
    fun `capability history is bounded and evicted cards cannot acknowledge`() {
        val fixture = Fixture()
        val registry = fixture.registry()
        val first = registry.issue("user:1", url)!!
        for (id in 2..513) registry.issue("user:$id", url)
        assertEquals(512, fixture.stored.size)
        assertFalse(registry.trusts("user:1", url, first))
    }
}
