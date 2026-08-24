package com.munitter.android.notification

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.webkit.CookieManager
import android.util.Log
import com.munitter.android.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class NotificationAvatarSpec(
    val actorUserId: Long,
    val relativeUrl: String,
    val version: String,
)

object NotificationAvatarCacheKey {
    fun from(spec: NotificationAvatarSpec): String {
        val material = "${spec.actorUserId}\n${spec.version}\n${spec.relativeUrl}"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}

class NotificationAvatarLoader(context: Context) {
    private val appContext = context.applicationContext
    private val cacheDirectory = File(appContext.cacheDir, CACHE_DIRECTORY_NAME)
    private val inFlightLocks = ConcurrentHashMap<String, Any>()

    fun specFor(actorUserId: Long?, relativeUrl: String, version: String): NotificationAvatarSpec? {
        if (actorUserId == null || actorUserId <= 0L || !isSafeAvatarPath(relativeUrl)) return null
        val normalizedVersion = version.trim().ifBlank { relativeUrl.trim() }
        return NotificationAvatarSpec(actorUserId, relativeUrl.trim(), normalizedVersion)
    }

    fun loadCached(spec: NotificationAvatarSpec): Bitmap? {
        val file = cacheFile(spec)
        if (!file.isFile) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }
            .onFailure { file.delete() }
            .getOrNull()
            ?.also { Log.d(TAG, "notification avatar cache hit actorId=${spec.actorUserId}") }
    }

    suspend fun loadOrFetch(spec: NotificationAvatarSpec): Bitmap? = withContext(Dispatchers.IO) {
        if (!BuildConfig.ENVIRONMENT.equals("development", ignoreCase = true)) return@withContext null
        loadCached(spec)?.let { return@withContext it }

        val key = NotificationAvatarCacheKey.from(spec)
        val lock = inFlightLocks.computeIfAbsent(key) { Any() }
        synchronized(lock) {
            try {
                loadCached(spec)?.let { return@withContext it }
                fetchAndCache(spec)
            } finally {
                inFlightLocks.remove(key, lock)
            }
        }
    }

    private fun fetchAndCache(spec: NotificationAvatarSpec): Bitmap? {
        val endpoint = BuildConfig.BASE_URL.trimEnd('/') + spec.relativeUrl
        val url = runCatching { URL(endpoint) }.getOrNull() ?: return null
        if (url.protocol != "https" || !url.host.equals(BuildConfig.INTERNAL_HOST, ignoreCase = true)) {
            Log.w(TAG, "notification avatar rejected: non-Development host actorId=${spec.actorUserId}")
            return null
        }

        val cookie = runCatching { CookieManager.getInstance().getCookie(BuildConfig.BASE_URL) }
            .getOrNull()
            .orEmpty()
        if (cookie.isBlank()) {
            Log.w(TAG, "notification avatar deferred: no session cookie actorId=${spec.actorUserId}")
            return null
        }

        val connection = runCatching { url.openConnection() as HttpURLConnection }.getOrNull()
            ?: return null
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "image/*")
            connection.setRequestProperty("Cookie", cookie)
            connection.setRequestProperty("User-Agent", BuildConfig.APP_UA_TOKEN)
            if (BuildConfig.DEVELOPMENT_DEBUG_CLIENT_HEADER.isNotBlank()) {
                connection.setRequestProperty(
                    "X-Munitter-Client",
                    BuildConfig.DEVELOPMENT_DEBUG_CLIENT_HEADER,
                )
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(
                    TAG,
                    "notification avatar request failed actorId=${spec.actorUserId} status=${connection.responseCode}",
                )
                return null
            }
            val bytes = connection.inputStream.use(::readBounded)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let(::normalize)
                ?: return null
            writeCache(spec, bitmap)
            Log.i(TAG, "notification avatar fetched actorId=${spec.actorUserId}")
            bitmap
        } catch (exception: Exception) {
            Log.w(TAG, "notification avatar fetch failed actorId=${spec.actorUserId}", exception)
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun writeCache(spec: NotificationAvatarSpec, bitmap: Bitmap) {
        if (!cacheDirectory.exists() && !cacheDirectory.mkdirs()) return
        val file = cacheFile(spec)
        val temporary = File(cacheDirectory, file.name + ".tmp")
        runCatching {
            FileOutputStream(temporary).use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            if (!temporary.renameTo(file)) {
                temporary.delete()
            }
            cacheDirectory.listFiles()
                ?.filter { it.name.startsWith("actor-${spec.actorUserId}-") && it != file }
                ?.forEach(File::delete)
        }.onFailure { temporary.delete() }
    }

    private fun cacheFile(spec: NotificationAvatarSpec): File =
        File(cacheDirectory, "actor-${spec.actorUserId}-${NotificationAvatarCacheKey.from(spec)}.png")

    private fun normalize(bitmap: Bitmap): Bitmap {
        val maxDimension = MAX_BITMAP_DIMENSION
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / largest.toFloat()
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_DOWNLOAD_BYTES) throw IllegalStateException("avatar-too-large")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun isSafeAvatarPath(value: String): Boolean {
        if (!value.startsWith("/profile/media/") || value.contains("://") || value.contains('\\')) {
            return false
        }
        return value.split('/', '?', '#').none { it == "." || it == ".." }
    }

    private companion object {
        const val CACHE_DIRECTORY_NAME = "munitter_notification_avatars"
        const val CONNECT_TIMEOUT_MS = 1_500
        const val READ_TIMEOUT_MS = 2_500
        const val MAX_DOWNLOAD_BYTES = 4 * 1024 * 1024
        const val MAX_BITMAP_DIMENSION = 128
        const val TAG = "MunitterNotifications"
    }
}
