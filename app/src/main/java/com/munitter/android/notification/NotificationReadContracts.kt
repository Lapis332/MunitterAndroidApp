package com.munitter.android.notification

import java.net.URI
import org.json.JSONObject

/** A tap is a read request, never evidence that the server accepted that read. */
internal object NotificationTapPolicy {
    fun ordinaryId(raw: String?): String? {
        if (raw == null || raw.length > 32) return null
        val match = ORDINARY_ID.matchEntire(raw) ?: return null
        val id = match.groupValues[2].toLongOrNull()?.takeIf { it > 0L } ?: return null
        return "${match.groupValues[1]}-$id"
    }

    fun isCurrentOrigin(raw: String?, internalHost: String): Boolean {
        val uri = runCatching { URI(raw ?: return false) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals(internalHost, ignoreCase = true) &&
            uri.userInfo == null && (uri.port == -1 || uri.port == 443)
    }

    fun fromIntent(action: String?, url: String?, id: String?, internalHost: String): String? =
        ordinaryId(id)?.takeIf {
            action == "android.intent.action.VIEW" && isCurrentOrigin(url, internalHost)
        }

    fun fromVerifiedIntent(action: String?, url: String?, id: String?, nonce: String?,
        internalHost: String, proofs: NotificationTapProofRegistry): String? =
        fromIntent(action, url, id, internalHost)?.takeIf { proofs.trusts(it, url, nonce) }

    private val ORDINARY_ID = Regex("^(user|group)[:-]([1-9][0-9]{0,18})$")
}

/** Bounded process-local work, independent from immediate WebView navigation. */
internal class NotificationTapQueue {
    private val pending = linkedSetOf<String>()

    fun offer(id: String) {
        val canonical = NotificationTapPolicy.ordinaryId(id) ?: return
        if (pending.size >= 16) return
        pending += canonical
    }

    fun first(): String? = pending.firstOrNull()

    fun discard(id: String) { pending.remove(id) }

    fun complete(id: String) {
        pending.remove(id)
    }
}

internal data class NotificationReadAcknowledgment(
    val acknowledgedIds: Set<String>,
    val unreadCount: Int,
)

internal object NotificationReadResponseParser {
    fun parse(json: String, requestedId: String): NotificationReadAcknowledgment? {
        val requested = NotificationTapPolicy.ordinaryId(requestedId) ?: return null
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (root.opt("success") != true) return null
        val ids = root.optJSONArray("acknowledgedNotificationIds") ?: return null
        if (ids.length() > 100) return null
        val acknowledged = mutableSetOf<String>()
        for (index in 0 until ids.length()) {
            val id = NotificationTapPolicy.ordinaryId(ids.opt(index) as? String) ?: return null
            // A response must not acknowledge a different request or account.
            if (id != requested) return null
            acknowledged += id
        }
        val count = NotificationDeviceUnreadParser.fromObject(root) ?: return null
        return NotificationReadAcknowledgment(acknowledged, count)
    }
}

internal object NotificationDeviceUnreadParser {
    fun parse(json: String): Int? = runCatching { fromObject(JSONObject(json)) }.getOrNull()

    fun fromObject(root: JSONObject): Int? {
        val raw = root.opt("deviceTotalUnreadCount") as? Number ?: return null
        return raw.toString().toLongOrNull()?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
    }
}

/** Only a CSRF cookie may be merged into this one request's in-memory header. */
internal object NotificationAntiforgeryCookie {
    const val NAME = "__Host-Munitter.Antiforgery"

    fun merge(original: String, setCookies: List<String>): String? {
        val issued = setCookies.map { it.substringBefore(';').trim() }
            .filter { it.substringBefore('=') == NAME }
        if (issued.size > 1) return null
        val csrf = issued.singleOrNull() ?: return original
        if (csrf.length > 8192 || csrf.substringAfter('=', "").isBlank() ||
            csrf.any { it == '\r' || it == '\n' || it == '\u0000' }) return null
        val previous = original.split(';').map(String::trim)
            .filter { it.isNotEmpty() && it.substringBefore('=') != NAME }
        return (previous + csrf).joinToString("; ")
    }
}
