package com.munitter.android.notification

import android.webkit.CookieManager
import com.munitter.android.BuildConfig
import java.io.BufferedInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

sealed interface NotificationFetchResult {
    data class Page(val value: NotificationPage) : NotificationFetchResult
    data object Unauthorized : NotificationFetchResult
    data class RetryableFailure(val cause: Throwable) : NotificationFetchResult
}

class MunitterNotificationRepository {
    fun fetchRefresh(): NotificationFetchResult = fetch("Notifications?handler=Refresh")

    fun fetchMore(offset: Int): NotificationFetchResult =
        fetch("Notifications?handler=More&offset=$offset")

    private fun fetch(path: String): NotificationFetchResult {
        val endpoint = BuildConfig.BASE_URL.trimEnd('/') + "/" + path
        val cookie = runCatching {
            CookieManager.getInstance().getCookie(BuildConfig.BASE_URL)
        }.getOrNull()
        if (cookie.isNullOrBlank()) {
            return NotificationFetchResult.Unauthorized
        }

        val connection = runCatching {
            URL(endpoint).openConnection() as HttpURLConnection
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
        return BufferedInputStream(stream).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private companion object {
        const val TIMEOUT_MS = 8_000
    }
}
