package com.munitter.android.notification

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import com.munitter.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class FcmTokenRegistrar(context: Context) {
    private val appContext = context.applicationContext
    private val store = FcmTokenStore(appContext)

    suspend fun registerIfPossible(): Boolean = withContext(Dispatchers.IO) {
        if (!BuildConfig.ENVIRONMENT.equals("development", ignoreCase = true)) return@withContext false
        val token = store.read() ?: return@withContext false
        val cookie = runCatching {
            CookieManager.getInstance().getCookie(BuildConfig.BASE_URL)
        }.getOrNull()
        if (cookie.isNullOrBlank()) return@withContext false
        var antiForgeryToken = store.antiForgeryToken() ?: fetchAntiForgeryToken(cookie)
            ?: return@withContext false
        var status = postRegistration(token, cookie, antiForgeryToken)
        if (status == HttpURLConnection.HTTP_BAD_REQUEST) {
            // The antiforgery token is session-bound. Refresh it once after a
            // server-side key/session rotation instead of retrying a stale value.
            store.clearAntiForgeryToken()
            val refreshedCookie = runCatching {
                CookieManager.getInstance().getCookie(BuildConfig.BASE_URL)
            }.getOrNull() ?: cookie
            antiForgeryToken = fetchAntiForgeryToken(refreshedCookie) ?: return@withContext false
            status = postRegistration(token, refreshedCookie, antiForgeryToken)
        }

        if (status in 200..299) {
            store.markRegistered(token)
            Log.i(TAG, "FCM token server registration succeeded status=$status")
            true
        } else {
            if (status == HttpURLConnection.HTTP_UNAUTHORIZED ||
                status == HttpURLConnection.HTTP_FORBIDDEN) {
                store.clearRegistered()
            }
            Log.w(TAG, "FCM token server registration rejected status=$status")
            false
        }
    }

    private fun postRegistration(token: String, cookie: String, antiForgeryToken: String): Int {
        val endpoint = BuildConfig.BASE_URL.trimEnd('/') + "/api/fcm/token"
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Cookie", cookie)
            connection.setRequestProperty("X-CSRF-TOKEN", antiForgeryToken)
            connection.setRequestProperty("User-Agent", BuildConfig.APP_UA_TOKEN)
            if (BuildConfig.DEVELOPMENT_DEBUG_CLIENT_HEADER.isNotBlank()) {
                connection.setRequestProperty(
                    "X-Munitter-Client",
                    BuildConfig.DEVELOPMENT_DEBUG_CLIENT_HEADER,
                )
            }
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(
                    JSONObject()
                        .put("token", token)
                        .put("platform", "Android")
                        .put("environment", "Development")
                        .put("applicationId", appContext.packageName)
                        .toString(),
                )
            }
            connection.responseCode
        } catch (exception: Exception) {
            Log.w(TAG, "FCM token server registration failed", exception)
            -1
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchAntiForgeryToken(cookie: String): String? {
        val endpoint = BuildConfig.BASE_URL.trimEnd('/') + "/api/security/antiforgery"
        val connection = runCatching { URL(endpoint).openConnection() as HttpURLConnection }
            .getOrElse { return null }
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cookie", cookie)
            connection.setRequestProperty("User-Agent", BuildConfig.APP_UA_TOKEN)
            if (BuildConfig.DEVELOPMENT_DEBUG_CLIENT_HEADER.isNotBlank()) {
                connection.setRequestProperty("X-Munitter-Client", BuildConfig.DEVELOPMENT_DEBUG_CLIENT_HEADER)
            }
            val status = connection.responseCode
            if (status !in 200..299) return null
            connection.headerFields["Set-Cookie"]
                ?.filterNotNull()
                ?.forEach { setCookie ->
                    setCookie.substringBefore(';').takeIf { it.contains('=') }?.let {
                        CookieManager.getInstance().setCookie(BuildConfig.BASE_URL, it)
                    }
                }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            JSONObject(body).optString("requestToken").trim().takeIf { it.isNotEmpty() }?.also(store::saveAntiForgeryToken)
        } catch (exception: Exception) {
            Log.w(TAG, "Antiforgery token retrieval failed", exception)
            null
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 8_000
        const val TAG = "MunitterFCM"
    }
}
