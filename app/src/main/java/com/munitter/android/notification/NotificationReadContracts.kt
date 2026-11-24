package com.munitter.android.notification

import java.net.URI
import org.json.JSONObject

internal data class NotificationDmIdentity(val messageId: Long, val recipientId: Long)

/** The peer is retained only from the exact, capability-bound DM target. */
internal data class NotificationTapRequest(val id: String, val dmPeerUserId: Int? = null)

/** A tap is a read request, never evidence that the server accepted that read. */
internal object NotificationTapPolicy {
    fun ordinaryId(raw: String?): String? {
        if (raw == null || raw.length > 32) return null
        val match = ORDINARY_ID.matchEntire(raw) ?: return null
        val id = match.groupValues[2].toLongOrNull()?.takeIf { it > 0L } ?: return null
        return "${match.groupValues[1]}-$id"
    }

    fun dmIdentity(raw: String?): NotificationDmIdentity? {
        if (raw == null || raw.length > 64) return null
        val match = DM_ID.matchEntire(raw) ?: return null
        val message = match.groupValues[1].toLongOrNull()?.takeIf { it > 0L } ?: return null
        val recipient = match.groupValues[2].toLongOrNull()?.takeIf { it > 0L } ?: return null
        return NotificationDmIdentity(message, recipient)
    }

    fun concreteId(raw: String?): String? = ordinaryId(raw) ?: dmIdentity(raw)?.let {
        "dm:${it.messageId}:${it.recipientId}"
    }

    fun validatedRequest(request: NotificationTapRequest): NotificationTapRequest? {
        val id = concreteId(request.id) ?: return null
        if (dmIdentity(id) != null) {
            if (request.dmPeerUserId == null || request.dmPeerUserId <= 0) return null
        } else if (request.dmPeerUserId != null) return null
        return request.copy(id = id)
    }

    fun requestForTarget(id: String?, target: String?, internalHost: String): NotificationTapRequest? {
        val canonical = concreteId(id) ?: return null
        if (target == null || target.length > 4096 || !isCurrentOrigin(target, internalHost)) return null
        if (dmIdentity(canonical) == null) return NotificationTapRequest(canonical)
        val uri = runCatching { URI(target) }.getOrNull() ?: return null
        if (uri.rawQuery != null || uri.rawFragment != null) return null
        val peer = DM_TARGET.matchEntire(uri.rawPath.orEmpty())?.groupValues?.get(1)
            ?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        return NotificationTapRequest(canonical, peer)
    }

    fun isCurrentOrigin(raw: String?, internalHost: String): Boolean {
        val uri = runCatching { URI(raw ?: return false) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals(internalHost, ignoreCase = true) &&
            uri.userInfo == null && (uri.port == -1 || uri.port == 443)
    }

    fun fromIntent(action: String?, url: String?, id: String?, internalHost: String): String? =
        requestForTarget(id, url, internalHost)?.id?.takeIf { action == "android.intent.action.VIEW" }

    fun requestFromVerifiedIntent(action: String?, url: String?, id: String?, nonce: String?,
        internalHost: String, proofs: NotificationTapProofRegistry): NotificationTapRequest? =
        requestForTarget(id, url, internalHost)?.takeIf {
            action == "android.intent.action.VIEW" && proofs.trusts(it.id, url, nonce)
        }

    fun fromVerifiedIntent(action: String?, url: String?, id: String?, nonce: String?,
        internalHost: String, proofs: NotificationTapProofRegistry): String? =
        requestFromVerifiedIntent(action, url, id, nonce, internalHost, proofs)?.id

    private val ORDINARY_ID = Regex("^(user|group)[:-]([1-9][0-9]{0,18})$")
    private val DM_ID = Regex("^dm:([1-9][0-9]{0,18}):([1-9][0-9]{0,18})$")
    private val DM_TARGET = Regex("^/dm/([1-9][0-9]{0,9})$")
}

/** Bounded process-local work, independent from immediate WebView navigation. */
internal class NotificationTapQueue {
    private val pending = linkedMapOf<String, NotificationTapRequest>()

    fun offer(id: String) {
        val canonical = NotificationTapPolicy.ordinaryId(id) ?: return
        offer(NotificationTapRequest(canonical))
    }

    fun offer(request: NotificationTapRequest) {
        val validated = NotificationTapPolicy.validatedRequest(request) ?: return
        if (pending.size >= 16) return
        pending.putIfAbsent(validated.id, validated)
    }

    fun first(): String? = pending.keys.firstOrNull()

    fun firstRequest(): NotificationTapRequest? = pending.values.firstOrNull()

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
        val requested = NotificationTapPolicy.concreteId(requestedId) ?: return null
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (root.opt("success") != true) return null
        val ids = root.optJSONArray("acknowledgedNotificationIds") ?: return null
        if (ids.length() > 100) return null
        val acknowledged = mutableSetOf<String>()
        for (index in 0 until ids.length()) {
            val id = NotificationTapPolicy.concreteId(ids.opt(index) as? String) ?: return null
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
