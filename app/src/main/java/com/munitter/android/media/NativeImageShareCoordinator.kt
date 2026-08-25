package com.munitter.android.media

import android.content.ClipData
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.munitter.android.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class NativeImageShareRequest(
    val url: String,
    val fileName: String,
    val contentType: String?,
    val quality: String,
)

class NativeImageSharePolicy(
    internalHost: String,
) {
    private val internalHost = internalHost.lowercase()
    val allowedOriginRule: String = "https://$internalHost"

    fun parse(message: String?): NativeImageShareRequest? {
        if (message.isNullOrBlank() || message.length > MAX_MESSAGE_LENGTH) return null
        val payload = runCatching { JSONObject(message) }.getOrNull() ?: return null
        if (payload.optInt("version", -1) != CURRENT_VERSION) return null

        val url = payload.optString("url")
        if (!isAllowed(url)) return null
        val rawContentType = payload.optString("contentType")
            .trim()
            .lowercase()
        val contentType = rawContentType.takeIf(IMAGE_MIME_PATTERN::matches)
        val quality = payload.optString("quality").takeIf { it == "hd" } ?: "display"

        return NativeImageShareRequest(
            url = url,
            fileName = sanitizeFileName(payload.optString("fileName")),
            contentType = contentType,
            quality = quality,
        )
    }

    fun isAllowed(rawUrl: String?): Boolean {
        val uri = runCatching { URI(rawUrl.orEmpty()) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals(internalHost, ignoreCase = true) &&
            uri.userInfo == null &&
            uri.port in setOf(-1, 443)
    }

    private fun sanitizeFileName(value: String): String {
        val cleaned = value
            .replace(INVALID_FILE_NAME_CHARS, "_")
            .trim()
            .take(MAX_FILE_NAME_LENGTH)
        return cleaned.ifBlank { "image" }
    }

    companion object {
        private const val CURRENT_VERSION = 1
        private const val MAX_MESSAGE_LENGTH = 8_192
        private const val MAX_FILE_NAME_LENGTH = 160
        private val INVALID_FILE_NAME_CHARS = Regex("""[\\/:*?"<>|\u0000-\u001F\u007F]""")
        private val IMAGE_MIME_PATTERN = Regex("""image/[a-z0-9.+-]+""", RegexOption.IGNORE_CASE)
    }
}

class NativeImageShareCoordinator(
    private val activity: ComponentActivity,
    internalHost: String,
) {
    private val policy = NativeImageSharePolicy(internalHost)
    private var attachedWebView: WebView? = null
    private var bridgeInstalled = false
    private var shareJob: Job? = null
    private var cleanupJob: Job? = null

    fun attach(webView: WebView): Boolean {
        detach()
        clearCachedImages()
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            return false
        }

        WebViewCompat.addWebMessageListener(
            webView,
            BRIDGE_NAME,
            setOf(policy.allowedOriginRule),
            object : WebViewCompat.WebMessageListener {
                override fun onPostMessage(
                    view: WebView,
                    message: WebMessageCompat,
                    sourceOrigin: Uri,
                    isMainFrame: Boolean,
                    replyProxy: JavaScriptReplyProxy,
                ) {
                    if (message.type != WebMessageCompat.TYPE_STRING) return
                    handleMessage(
                        webView = view,
                        message = message.data,
                        sourceOrigin = sourceOrigin.toString(),
                        isMainFrame = isMainFrame,
                    )
                }
            },
        )
        attachedWebView = webView
        bridgeInstalled = true
        return true
    }

    fun detach() {
        shareJob?.cancel()
        shareJob = null
        cleanupJob?.cancel()
        cleanupJob = null
        val webView = attachedWebView
        if (
            bridgeInstalled &&
            webView != null &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        ) {
            runCatching { WebViewCompat.removeWebMessageListener(webView, BRIDGE_NAME) }
        }
        bridgeInstalled = false
        attachedWebView = null
        clearCachedImages()
    }

    private fun handleMessage(
        webView: WebView,
        message: String?,
        sourceOrigin: String,
        isMainFrame: Boolean,
    ) {
        if (
            !isMainFrame ||
            webView !== attachedWebView ||
            !policy.isAllowed(sourceOrigin) ||
            !policy.isAllowed(webView.url) ||
            shareJob != null
        ) {
            return
        }
        val request = policy.parse(message) ?: return
        val cookie = runCatching {
            CookieManager.getInstance().getCookie(request.url)
        }.getOrNull()
        val userAgent = webView.settings.userAgentString.orEmpty()

        shareJob = activity.lifecycleScope.launch {
            try {
                val sharedImage = withContext(Dispatchers.IO) {
                    loadImage(request, cookie, userAgent)
                }
                launchShareSheet(sharedImage)
                scheduleCleanup()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                clearCachedImages()
                Toast.makeText(activity, R.string.image_share_failed, Toast.LENGTH_SHORT).show()
            } finally {
                shareJob = null
            }
        }
    }

    private fun loadImage(
        request: NativeImageShareRequest,
        cookie: String?,
        userAgent: String,
    ): SharedImage {
        val directory = prepareCacheDirectory()
        val temporary = File(directory, "image.part")
        val connection = openFollowingSafeRedirects(request.url, cookie, userAgent)
        try {
            val mimeType = connection.contentType
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.startsWith("image/") }
                ?: error("The response is not an image.")
            val announcedLength = connection.contentLengthLong
            if (announcedLength > MAX_IMAGE_BYTES) {
                error("The image is too large.")
            }

            connection.inputStream.buffered().use { input ->
                BufferedOutputStream(FileOutputStream(temporary)).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_IMAGE_BYTES) {
                            error("The image is too large.")
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                error("The image data is invalid.")
            }

            val destination = File(
                directory,
                destinationFileName(request.fileName, mimeType),
            )
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
            return SharedImage(destination, mimeType)
        } catch (exception: Exception) {
            temporary.delete()
            throw exception
        } finally {
            connection.disconnect()
        }
    }

    private fun openFollowingSafeRedirects(
        rawUrl: String,
        cookie: String?,
        userAgent: String,
    ): HttpsURLConnection {
        var current = URL(rawUrl)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            if (!policy.isAllowed(current.toString())) {
                error("Blocked image redirect.")
            }
            val connection = current.openConnection() as? HttpsURLConnection
                ?: error("Only HTTPS images are supported.")
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.useCaches = false
            connection.setRequestProperty("Accept", "image/*")
            if (cookie?.isNotBlank() == true) {
                connection.setRequestProperty("Cookie", cookie)
            }
            if (userAgent.isNotBlank()) {
                connection.setRequestProperty("User-Agent", userAgent)
            }

            val status = connection.responseCode
            if (status in HTTP_REDIRECTS) {
                if (redirectCount == MAX_REDIRECTS) {
                    connection.disconnect()
                    error("Too many image redirects.")
                }
                val location = connection.getHeaderField("Location")
                    ?: run {
                        connection.disconnect()
                        error("The image redirect has no location.")
                    }
                connection.disconnect()
                current = current.toURI().resolve(location).toURL()
                return@repeat
            }
            if (status !in 200..299) {
                connection.errorStream?.close()
                connection.disconnect()
                error("Image download failed.")
            }
            return connection
        }
        error("Image download failed.")
    }

    private fun launchShareSheet(image: SharedImage) {
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            image.file,
        )
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = image.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(
                activity.contentResolver,
                image.file.name,
                uri,
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(
            sendIntent,
            activity.getString(R.string.share_image_title),
        ).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(chooser)
    }

    private fun scheduleCleanup() {
        cleanupJob?.cancel()
        cleanupJob = activity.lifecycleScope.launch {
            delay(CACHE_RETENTION_MILLIS)
            withContext(Dispatchers.IO) { clearCachedImages() }
        }
    }

    private fun prepareCacheDirectory(): File {
        val directory = cacheDirectory()
        directory.deleteRecursively()
        if (!directory.mkdirs() && !directory.isDirectory) {
            error("The image share cache is unavailable.")
        }
        return directory
    }

    private fun clearCachedImages() {
        runCatching { cacheDirectory().deleteRecursively() }
    }

    private fun cacheDirectory(): File = File(activity.cacheDir, CACHE_DIRECTORY_NAME)

    private fun destinationFileName(requestedName: String, mimeType: String): String {
        val stem = requestedName
            .substringBeforeLast('.', requestedName)
            .trimEnd('.')
            .ifBlank { "image" }
        val extension = when (mimeType) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/heic", "image/heif" -> "heic"
            "image/avif" -> "avif"
            else -> "img"
        }
        return "$stem.$extension"
    }

    private data class SharedImage(
        val file: File,
        val mimeType: String,
    )

    companion object {
        const val BRIDGE_NAME = "MunitterImageShare"
        private const val CACHE_DIRECTORY_NAME = "shared-images"
        private const val MAX_REDIRECTS = 5
        private const val MAX_IMAGE_BYTES = 80L * 1_024 * 1_024
        private const val COPY_BUFFER_SIZE = 64 * 1_024
        private const val CONNECT_TIMEOUT_MILLIS = 30_000
        private const val READ_TIMEOUT_MILLIS = 60_000
        private const val CACHE_RETENTION_MILLIS = 10 * 60 * 1_000L
        private val HTTP_REDIRECTS = setOf(
            HttpURLConnection.HTTP_MOVED_PERM,
            HttpURLConnection.HTTP_MOVED_TEMP,
            HttpURLConnection.HTTP_SEE_OTHER,
            307,
            308,
        )
    }
}
