package com.munitter.android.notification

// These classes intentionally have no generated toString containing token values.
internal class FcmTokenCacheValues(
    val binding: String?,
    val token: String?,
    val registeredToken: String?,
)

internal class FcmTokenSnapshot(val token: String, val binding: String)

internal class FcmTokenLookup(val binding: String, val generation: Long)

// SDK callbacks cannot survive a process restart. Every store in this process
// shares this clock, so onNewToken also supersedes pending activity lookups.
internal class FcmTokenCacheGeneration(var value: Long = 0)

internal interface FcmTokenCacheStorage {
    fun read(): FcmTokenCacheValues
    fun write(values: FcmTokenCacheValues)
}

/** The same lock is shared by every Android storage adapter in this process. */
internal class FcmTokenCache(
    private val storage: FcmTokenCacheStorage,
    private val activeBinding: () -> String?,
    private val lock: Any,
    private val generation: FcmTokenCacheGeneration = FcmTokenCacheGeneration(),
) {
    fun captureBinding(): String? = synchronized(lock) {
        activeBinding()?.also { prepare(it) }
    }

    fun captureLookup(): FcmTokenLookup? = synchronized(lock) {
        val active = activeBinding() ?: return@synchronized null
        prepare(active)
        if (active == FcmTokenProjectBinding.LEGACY_PRODUCTION) {
            return@synchronized FcmTokenLookup(active, 0)
        }
        advanceGeneration()
        if (generation.value == Long.MAX_VALUE) return@synchronized null
        FcmTokenLookup(active, generation.value)
    }

    fun publishLookup(token: String, lookup: FcmTokenLookup): Boolean = synchronized(lock) {
        val active = activeBinding() ?: return@synchronized false
        if (active != lookup.binding ||
            (active != FcmTokenProjectBinding.LEGACY_PRODUCTION && generation.value != lookup.generation)) {
            return@synchronized false
        }
        save(token, active)
    }

    fun save(token: String, expectedBinding: String?): Boolean = synchronized(lock) {
        val active = activeBinding() ?: return@synchronized false
        if (active != expectedBinding || token.isBlank()) return@synchronized false
        val values = prepare(active)
        if (active != FcmTokenProjectBinding.LEGACY_PRODUCTION) advanceGeneration()
        storage.write(FcmTokenCacheValues(values.binding, token.trim(), values.registeredToken))
        true
    }

    fun snapshot(): FcmTokenSnapshot? = synchronized(lock) {
        val active = activeBinding() ?: return@synchronized null
        val token = prepare(active).token?.trim()?.takeIf(String::isNotEmpty)
            ?: return@synchronized null
        FcmTokenSnapshot(token, active)
    }

    fun isCurrent(snapshot: FcmTokenSnapshot): Boolean = synchronized(lock) {
        isCurrentLocked(snapshot)
    }

    fun markRegistered(snapshot: FcmTokenSnapshot): Boolean = synchronized(lock) {
        if (!isCurrentLocked(snapshot)) return@synchronized false
        val values = storage.read()
        storage.write(FcmTokenCacheValues(values.binding, values.token, snapshot.token))
        true
    }

    fun clearRegistered(snapshot: FcmTokenSnapshot? = null) = synchronized(lock) {
        if (snapshot != null && !isCurrentLocked(snapshot)) return@synchronized
        val active = activeBinding() ?: return@synchronized
        val values = prepare(active)
        storage.write(FcmTokenCacheValues(values.binding, values.token, null))
    }

    fun registeredToken(): String? = synchronized(lock) {
        val active = activeBinding() ?: return@synchronized null
        prepare(active).registeredToken?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun isCurrentLocked(snapshot: FcmTokenSnapshot): Boolean {
        val active = activeBinding() ?: return false
        if (active != snapshot.binding) return false
        // The new project/token generation fence is scoped to Development.
        if (active == FcmTokenProjectBinding.LEGACY_PRODUCTION) return true
        return prepare(active).token?.trim() == snapshot.token
    }

    private fun prepare(active: String): FcmTokenCacheValues {
        val values = storage.read()
        if (active == FcmTokenProjectBinding.LEGACY_PRODUCTION || values.binding == active) {
            return values
        }
        // One storage operation invalidates both values from the previous project.
        advanceGeneration()
        return FcmTokenCacheValues(active, null, null).also(storage::write)
    }

    private fun advanceGeneration() {
        if (generation.value < Long.MAX_VALUE) generation.value += 1
    }
}
