package com.munitter.android.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FcmTokenCacheTest {
    private class Storage(initial: FcmTokenCacheValues) : FcmTokenCacheStorage {
        var values = initial
        var writes = 0
        override fun read() = values
        override fun write(values: FcmTokenCacheValues) {
            this.values = values
            writes += 1
        }
    }

    private fun binding(
        project: String = "dev-project-a",
        app: String = "1:123:android:aaa",
        sender: String = "123",
        packageName: String = "com.munitter.android.development.debug",
    ) = FcmTokenProjectBinding.create("development", project, project, app, sender, packageName)!!

    @Test
    fun `untagged Dev cache cannot register before a fresh SDK token is published`() {
        val active = binding()
        val storage = Storage(FcmTokenCacheValues(null, "old-token", "old-token"))
        val cache = FcmTokenCache(storage, { active }, Any())
        assertEquals(active, cache.captureBinding())
        assertNull(cache.snapshot())
        assertNull(cache.registeredToken())
        assertEquals(1, storage.writes)
        // A failed SDK lookup publishes nothing and cannot recover the old value.
        assertNull(cache.snapshot())
        assertTrue(cache.save("fresh-token", active))
        assertEquals("fresh-token", cache.snapshot()!!.token)
    }

    @Test
    fun `late callback from an old project cannot overwrite its successor`() {
        var active = binding()
        val storage = Storage(FcmTokenCacheValues(active, "old-token", "old-token"))
        val cache = FcmTokenCache(storage, { active }, Any())
        val capturedOldBinding = cache.captureBinding()
        active = binding(project = "dev-project-b")
        val nextBinding = cache.captureBinding()
        assertNull(cache.snapshot())
        assertTrue(cache.save("new-token", nextBinding))
        assertFalse(cache.save("late-old-token", capturedOldBinding))
        assertEquals("new-token", cache.snapshot()!!.token)
        assertNull(cache.registeredToken())
    }

    @Test
    fun `old HTTP completion cannot mark or clear a newer project registration`() {
        var active = binding()
        val storage = Storage(FcmTokenCacheValues(active, "old-token", null))
        val cache = FcmTokenCache(storage, { active }, Any())
        val oldRequest = cache.snapshot()!!
        active = binding(project = "dev-project-b")
        assertTrue(cache.save("new-token", cache.captureBinding()))
        val newRequest = cache.snapshot()!!
        assertTrue(cache.markRegistered(newRequest))
        assertFalse(cache.isCurrent(oldRequest))
        assertFalse(cache.markRegistered(oldRequest))
        cache.clearRegistered(oldRequest)
        assertEquals("new-token", cache.registeredToken())
    }

    @Test
    fun `same-project SDK token rotation fences an earlier in-flight request`() {
        val active = binding()
        val storage = Storage(FcmTokenCacheValues(active, "token-one", null))
        val cache = FcmTokenCache(storage, { active }, Any())
        val firstRequest = cache.snapshot()!!
        assertTrue(cache.isCurrent(firstRequest))
        assertTrue(cache.save("token-two", active))
        assertFalse(cache.isCurrent(firstRequest))
        assertFalse(cache.markRegistered(firstRequest))
        val secondRequest = cache.snapshot()!!
        assertTrue(cache.markRegistered(secondRequest))
        assertEquals("token-two", cache.registeredToken())
    }

    @Test
    fun `onNewToken in another store supersedes a pending same-project SDK lookup`() {
        val active = binding()
        val storage = Storage(FcmTokenCacheValues(active, "token-one", null))
        val lock = Any()
        val generation = FcmTokenCacheGeneration()
        val activity = FcmTokenCache(storage, { active }, lock, generation)
        val service = FcmTokenCache(storage, { active }, lock, generation)
        val pending = activity.captureLookup()!!
        assertTrue(service.save("token-two", service.captureBinding()))
        assertFalse(activity.publishLookup("token-one", pending))
        assertEquals("token-two", activity.snapshot()!!.token)
    }

    @Test
    fun `newer SDK lookup supersedes an earlier callback in the same project`() {
        val active = binding()
        val storage = Storage(FcmTokenCacheValues(active, "old-token", null))
        val cache = FcmTokenCache(storage, { active }, Any())
        val earlier = cache.captureLookup()!!
        val later = cache.captureLookup()!!
        assertFalse(cache.publishLookup("earlier-token", earlier))
        assertTrue(cache.publishLookup("latest-token", later))
        assertEquals("latest-token", cache.snapshot()!!.token)
    }

    @Test
    fun `matching project cache survives another store instance and restart`() {
        val active = binding()
        val storage = Storage(FcmTokenCacheValues(active, "cached-token", "cached-token"))
        val lock = Any()
        val first = FcmTokenCache(storage, { active }, lock)
        val second = FcmTokenCache(storage, { active }, lock)
        assertEquals("cached-token", first.snapshot()!!.token)
        assertEquals("cached-token", second.registeredToken())
        assertEquals(0, storage.writes)
        assertTrue(first.save("updated-token", first.captureBinding()))
        assertEquals("updated-token", second.snapshot()!!.token)
    }

    @Test
    fun `unavailable SDK identity cannot expose cached tokens or publish a callback`() {
        val previous = binding()
        val storage = Storage(FcmTokenCacheValues(previous, "cached-token", "cached-token"))
        val cache = FcmTokenCache(storage, { null }, Any())
        assertNull(cache.captureBinding())
        assertNull(cache.snapshot())
        assertNull(cache.registeredToken())
        assertFalse(cache.save("unbound-token", previous))
        assertFalse(cache.markRegistered(FcmTokenSnapshot("cached-token", previous)))
        assertEquals(0, storage.writes)
    }

    @Test
    fun `app or sender identity changes invalidate a cache even in the same project`() {
        val original = binding()
        for (changed in listOf(binding(app = "1:123:android:bbb"), binding(sender = "456"))) {
            val storage = Storage(FcmTokenCacheValues(original, "old-token", "old-token"))
            val cache = FcmTokenCache(storage, { changed }, Any())
            assertNull(cache.snapshot())
            assertNull(cache.registeredToken())
            assertEquals(changed, storage.values.binding)
        }
    }

    @Test
    fun `empty callback cannot erase a current token and normal tokens are trimmed`() {
        val active = binding()
        val storage = Storage(FcmTokenCacheValues(active, "old-token", null))
        val cache = FcmTokenCache(storage, { active }, Any())
        assertFalse(cache.save("  ", active))
        assertEquals("old-token", cache.snapshot()!!.token)
        assertTrue(cache.save("  new-token  ", active))
        assertEquals("new-token", cache.snapshot()!!.token)
    }

    @Test
    fun `a current rejected request can clear its own registration marker`() {
        val active = binding()
        val storage = Storage(FcmTokenCacheValues(active, "token", "token"))
        val cache = FcmTokenCache(storage, { active }, Any())
        cache.clearRegistered(cache.snapshot()!!)
        assertNull(cache.registeredToken())
        assertEquals("token", cache.snapshot()!!.token)
    }

    @Test
    fun `Production retains its existing untagged cache behavior`() {
        val active = FcmTokenProjectBinding.create("production", "unused", null, null, null, "unused")!!
        val storage = Storage(FcmTokenCacheValues(null, "existing-token", "existing-token"))
        val cache = FcmTokenCache(storage, { active }, Any())
        assertEquals("existing-token", cache.snapshot()!!.token)
        assertEquals("existing-token", cache.registeredToken())
        assertEquals(0, storage.writes)
        assertTrue(cache.save("updated-token", active))
        assertNull(storage.values.binding)
        assertEquals("existing-token", cache.registeredToken())
    }

    @Test
    fun `a foreign SDK project cannot bind a Development cache`() {
        assertNull(FcmTokenProjectBinding.create(
            "development", "dev-project-a", "dev-project-b", "app", "sender",
            "com.munitter.android.development.debug",
        ))
    }

    @Test
    fun `unsupported environment or package and incomplete SDK options fail closed`() {
        for (environment in listOf("Development", "staging", "")) {
            assertNull(FcmTokenProjectBinding.create(
                environment, "dev-project-a", "dev-project-a", "app", "sender",
                "com.munitter.android.development.debug",
            ))
        }
        assertNull(FcmTokenProjectBinding.create(
            "development", "dev-project-a", "dev-project-a", "app", "sender",
            "com.munitter.android",
        ))
        for (options in listOf(listOf(null, "sender"), listOf("app", null), listOf(" app", "sender"))) {
            assertNull(FcmTokenProjectBinding.create(
                "development", "dev-project-a", "dev-project-a", options[0], options[1],
                "com.munitter.android.development.debug",
            ))
        }
    }

    @Test
    fun `Development Debug and Release identities cannot share a cache binding`() {
        assertNotEquals(binding(), binding(packageName = "com.munitter.android.development"))
    }

    @Test
    fun `length-prefixed SDK fields cannot alias by moving a separator`() {
        assertNotEquals(binding(app = "a:1", sender = "b"), binding(app = "a", sender = "1:b"))
    }
}
