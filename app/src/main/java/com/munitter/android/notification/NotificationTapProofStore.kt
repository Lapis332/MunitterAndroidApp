package com.munitter.android.notification

import android.content.Context
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

// Deliberately not a data class: a PendingIntent capability must not stringify.
internal class NotificationTapProof(val targetUrl: String, val nonce: String)

internal class NotificationTapProofRegistry(
    private val internalHost: String,
    private val read: () -> Map<String, NotificationTapProof>,
    private val write: (Map<String, NotificationTapProof>) -> Boolean,
    private val createNonce: () -> String,
) {
    @Synchronized
    fun issue(id: String, targetUrl: String): String? {
        val canonical = NotificationTapPolicy.ordinaryId(id) ?: return null
        if (!safeTarget(targetUrl)) return null
        val before = read()
        before[canonical]?.takeIf { it.targetUrl == targetUrl && NONCE.matches(it.nonce) }
            ?.let { return it.nonce }
        val nonce = createNonce().takeIf(NONCE::matches) ?: return null
        val next = LinkedHashMap(before)
        next.remove(canonical)
        next[canonical] = NotificationTapProof(targetUrl, nonce)
        while (next.size > MAX_RECORDS) next.remove(next.keys.first())
        return nonce.takeIf { write(next) }
    }

    @Synchronized
    fun trusts(id: String, targetUrl: String?, nonce: String?): Boolean {
        val canonical = NotificationTapPolicy.ordinaryId(id) ?: return false
        if (targetUrl == null || !safeTarget(targetUrl) || nonce == null || !NONCE.matches(nonce)) return false
        val expected = read()[canonical] ?: return false
        return expected.targetUrl == targetUrl && MessageDigest.isEqual(
            expected.nonce.toByteArray(Charsets.UTF_8), nonce.toByteArray(Charsets.UTF_8))
    }

    @Synchronized
    fun remove(id: String) {
        val canonical = NotificationTapPolicy.ordinaryId(id) ?: return
        val next = LinkedHashMap(read())
        if (next.remove(canonical) != null) write(next)
    }

    @Synchronized
    fun clear() { write(emptyMap()) }

    private fun safeTarget(target: String): Boolean = target.length <= 4096 &&
        NotificationTapPolicy.isCurrentOrigin(target, internalHost)

    companion object {
        const val MAX_RECORDS = 512
        private val NONCE = Regex("^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$")
    }
}

/** App-private, backup-disabled notification state; never an API credential. */
internal class NotificationTapProofStore(context: Context) {
    val registry: NotificationTapProofRegistry = synchronized(Companion) {
        shared ?: context.applicationContext.getSharedPreferences("munitter_notifications", Context.MODE_PRIVATE)
            .let { preferences ->
                NotificationTapProofRegistry(com.munitter.android.BuildConfig.INTERNAL_HOST,
                    read = {
                        val encoded = preferences.getString(KEY, null)
                        if (encoded == null || encoded.length > 3 * 1024 * 1024) emptyMap()
                        else runCatching {
                            val array = JSONArray(encoded)
                            if (array.length() > NotificationTapProofRegistry.MAX_RECORDS) emptyMap()
                            else buildMap {
                                for (index in 0 until array.length()) {
                                    val row = array.optJSONObject(index) ?: continue
                                    val id = NotificationTapPolicy.ordinaryId(row.optString("id")) ?: continue
                                    put(id, NotificationTapProof(row.optString("target"), row.optString("nonce")))
                                }
                            }
                        }.getOrDefault(emptyMap())
                    },
                    write = { values ->
                        val array = JSONArray()
                        values.forEach { (id, proof) -> array.put(JSONObject().put("id", id)
                            .put("target", proof.targetUrl).put("nonce", proof.nonce)) }
                        preferences.edit().putString(KEY, array.toString()).commit()
                    },
                    createNonce = { UUID.randomUUID().toString() },
                ).also { shared = it }
            }
    }

    private companion object {
        const val KEY = "notification_tap_proofs_v1"
        var shared: NotificationTapProofRegistry? = null
    }
}
