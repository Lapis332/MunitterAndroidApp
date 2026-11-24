package com.munitter.android.notification

import android.webkit.CookieManager
import com.munitter.android.BuildConfig
import java.io.BufferedInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream
import org.json.JSONArray
import org.json.JSONObject

sealed interface NotificationFetchResult {
    data class Page(val value: NotificationPage) : NotificationFetchResult
    data object Unauthorized : NotificationFetchResult
    data class RetryableFailure(val cause: Throwable) : NotificationFetchResult
}

internal sealed interface NotificationReadResult {
    data class Acknowledged(val value: NotificationReadAcknowledgment) : NotificationReadResult
    data object NotAcknowledged : NotificationReadResult
    data object Unauthorized : NotificationReadResult
    data object RetryableFailure : NotificationReadResult
}

internal sealed interface NotificationCountResult {
    data class Count(val value: Int) : NotificationCountResult
    data object Unauthorized : NotificationCountResult
    data object RetryableFailure : NotificationCountResult
}

internal class MunitterNotificationRepository(
    readCookie: () -> String? = {
        runCatching { CookieManager.getInstance().getCookie(BuildConfig.BASE_URL) }.getOrNull()
    },
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    private val sessionFence = NotificationSessionFence(readCookie)

    fun captureSession(): NotificationRequestSession = sessionFence.capture()

    fun isCurrentSession(session: NotificationRequestSession): Boolean = sessionFence.isCurrent(session)

    fun fetchRefresh(session: NotificationRequestSession): NotificationFetchResult =
        fetch("Notifications?handler=Refresh", session)

    fun fetchMore(offset: Int, session: NotificationRequestSession): NotificationFetchResult =
        fetch("Notifications?handler=More&offset=$offset", session)

    fun fetchDeviceUnreadCount(session: NotificationRequestSession): NotificationCountResult {
        val cookie = session.cookie ?: return NotificationCountResult.Unauthorized
        val response = request("api/realtime/ui-state", cookie) ?: return NotificationCountResult.RetryableFailure
        if (response.status == 401 || response.status == 403) return NotificationCountResult.Unauthorized
        if (response.status != 200) return NotificationCountResult.RetryableFailure
        val count = NotificationDeviceUnreadParser.parse(response.body) ?: return NotificationCountResult.RetryableFailure
        return NotificationCountResult.Count(count)
    }

    fun acknowledgeRead(id: String, session: NotificationRequestSession): NotificationReadResult {
        val canonical = NotificationTapPolicy.ordinaryId(id) ?: return NotificationReadResult.RetryableFailure
        return acknowledgeRead(NotificationTapRequest(canonical), session)
    }

    fun acknowledgeRead(tap: NotificationTapRequest, session: NotificationRequestSession): NotificationReadResult {
        val validated = NotificationTapPolicy.validatedRequest(tap) ?: return NotificationReadResult.RetryableFailure
        val canonical = validated.id
        val cookie = session.cookie ?: return NotificationReadResult.Unauthorized
        if (!isCurrentSession(session)) return NotificationReadResult.RetryableFailure
        val csrf = request("api/security/antiforgery", cookie) ?: return NotificationReadResult.RetryableFailure
        if (csrf.status == 401 || csrf.status == 403) return NotificationReadResult.Unauthorized
        if (csrf.status != 200 || !isCurrentSession(session)) return NotificationReadResult.RetryableFailure
        val token = runCatching { JSONObject(csrf.body).opt("requestToken") as? String }.getOrNull()
            ?.takeIf { it.isNotBlank() && it.length <= 8192 && it.none { c -> c == '\r' || c == '\n' } }
            ?: return NotificationReadResult.RetryableFailure
        val requestCookie = NotificationAntiforgeryCookie.merge(cookie, csrf.setCookies)
            ?: return NotificationReadResult.RetryableFailure
        val dm = NotificationTapPolicy.dmIdentity(canonical)
        val endpoint = if (dm == null) "Notifications?handler=MarkItemRead"
            else "dm/${validated.dmPeerUserId}?handler=MarkRead"
        val body = if (dm == null) JSONObject().put("notificationIds", JSONArray().put(canonical)).toString()
            else "latestMessageId=${dm.messageId}&notificationId=${URLEncoder.encode(canonical, Charsets.UTF_8.name())}"
        if (!isCurrentSession(session)) return NotificationReadResult.RetryableFailure
        val response = request(endpoint, requestCookie, body, token, formEncoded = dm != null)
            ?: return NotificationReadResult.RetryableFailure
        if (!isCurrentSession(session)) return NotificationReadResult.RetryableFailure
        if (response.status == 401 || response.status == 403) return NotificationReadResult.Unauthorized
        if (response.status == 400 || response.status == 404) return NotificationReadResult.NotAcknowledged
        if (response.status != 200) return NotificationReadResult.RetryableFailure
        val acknowledgment = NotificationReadResponseParser.parse(response.body, canonical)
            ?: return NotificationReadResult.RetryableFailure
        return NotificationReadResult.Acknowledged(acknowledgment)
    }

    private data class Response(val status: Int, val body: String, val setCookies: List<String>)

    private fun request(path: String, cookie: String, body: String? = null, csrfToken: String? = null,
        formEncoded: Boolean = false): Response? {
        val connection = runCatching {
            openConnection(URL(BuildConfig.BASE_URL.trimEnd('/') + "/" + path))
        }.getOrNull() ?: return null
        return try {
            connection.requestMethod = if (body == null) "GET" else "POST"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Encoding", "gzip")
            connection.setRequestProperty("Cookie", cookie)
            connection.setRequestProperty("User-Agent", BuildConfig.APP_UA_TOKEN)
            if (BuildConfig.DEVELOPMENT_DEBUG_CLIENT_HEADER.isNotBlank()) {
                connection.setRequestProperty("X-Munitter-Client", BuildConfig.DEVELOPMENT_DEBUG_CLIENT_HEADER)
            }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type",
                    if (formEncoded) "application/x-www-form-urlencoded" else "application/json")
                connection.setRequestProperty("X-CSRF-TOKEN", csrfToken)
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            }
            val status = connection.responseCode
            val responseBody = if (status == 200) {
                connection.inputStream.readResponseBody(connection.getHeaderField("Content-Encoding"))
            } else ""
            Response(status, responseBody,
                connection.headerFields.entries.filter { it.key.equals("Set-Cookie", ignoreCase = true) }
                    .flatMap { it.value.orEmpty() }.filterNotNull())
        } catch (_: Exception) {
            // Never log request cookies, CSRF material, response bodies or URLs.
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun fetch(path: String, session: NotificationRequestSession): NotificationFetchResult {
        val endpoint = BuildConfig.BASE_URL.trimEnd('/') + "/" + path
        val cookie = session.cookie
        if (cookie.isNullOrBlank()) {
            return NotificationFetchResult.Unauthorized
        }

        val connection = runCatching {
            openConnection(URL(endpoint))
        }.getOrElse { return NotificationFetchResult.RetryableFailure(it) }

        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Encoding", "gzip")
            connection.setRequestProperty("Cookie", cookie)
            connection.setRequestProperty("User-Agent", BuildConfig.APP_UA_TOKEN)
            if (BuildConfig.DEVELOPMENT_DEBUG_CLIENT_HEADER.isNotBlank()) {
                connection.setRequestProperty(
                    "X-Munitter-Client",
                    BuildConfig.DEVELOPMENT_DEBUG_CLIENT_HEADER,
                )
            }

            when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_UNAUTHORIZED,
                HttpURLConnection.HTTP_FORBIDDEN,
                -> NotificationFetchResult.Unauthorized
                HttpURLConnection.HTTP_OK -> {
                    val body = connection.inputStream.readResponseBody(
                        connection.getHeaderField("Content-Encoding"),
                    )
                    NotificationFetchResult.Page(NotificationPageParser.parse(body))
                }
                else -> NotificationFetchResult.RetryableFailure(
                    IOException("Notification sync HTTP $status"),
                )
            }
        } catch (exception: Exception) {
            NotificationFetchResult.RetryableFailure(exception)
        } finally {
            connection.disconnect()
        }
    }

    private fun java.io.InputStream.readResponseBody(contentEncoding: String?): String {
        val stream = if (contentEncoding?.contains("gzip", ignoreCase = true) == true) {
            GZIPInputStream(this)
        } else {
            this
        }
        return BufferedInputStream(stream).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_RESPONSE_BYTES) throw IOException("Notification response exceeds limit")
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }

    private companion object {
        const val TIMEOUT_MS = 8_000
        const val MAX_RESPONSE_BYTES = 1024 * 1024
    }
}
