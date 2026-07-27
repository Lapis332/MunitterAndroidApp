package com.munitter.android.download

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContract
import androidx.lifecycle.lifecycleScope
import com.munitter.android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class SecureDownloadCoordinator(
    private val activity: ComponentActivity,
    internalHost: String,
) {
    private val policy = SecureDownloadPolicy(internalHost)
    private var pendingRequest: DownloadRequest? = null

    private val createDocument = activity.registerForActivityResult(
        CreateDownloadDocumentContract(),
    ) { destination ->
        val request = pendingRequest
        pendingRequest = null
        if (destination != null && request != null) {
            download(request, destination)
        }
    }

    fun requestDownload(
        rawUrl: String?,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
    ) {
        if (!policy.isAllowed(rawUrl) || pendingRequest != null) {
            Toast.makeText(activity, R.string.download_failed, Toast.LENGTH_SHORT).show()
            return
        }

        val url = checkNotNull(rawUrl)
        val resolvedMime = mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        val fileName = sanitizeFileName(
            URLUtil.guessFileName(
                urlWithoutQueryOrFragment(url),
                contentDisposition,
                resolvedMime,
            ),
        )
        val cookie = if (policy.isInternal(url)) {
            CookieManager.getInstance().getCookie(url)
        } else {
            null
        }

        pendingRequest = DownloadRequest(
            url = url,
            userAgent = userAgent.orEmpty(),
            mimeType = resolvedMime,
            internalCookie = cookie,
        )
        createDocument.launch(
            DownloadDocumentTarget(
                fileName = fileName,
                mimeType = resolvedMime,
            ),
        )
    }

    private fun download(request: DownloadRequest, destination: Uri) {
        activity.lifecycleScope.launch {
            val succeeded = withContext(Dispatchers.IO) {
                runCatching {
                    val connection = openFollowingSafeRedirects(request)
                    try {
                        activity.contentResolver.openOutputStream(destination, "w")
                            ?.buffered()
                            ?.use { output ->
                                connection.inputStream.buffered().use { input ->
                                    input.copyTo(output)
                                }
                            }
                            ?: error("The selected destination cannot be opened.")
                    } finally {
                        connection.disconnect()
                    }
                }.isSuccess
            }

            if (!succeeded) {
                runCatching { activity.contentResolver.delete(destination, null, null) }
            }
            Toast.makeText(
                activity,
                if (succeeded) R.string.download_complete else R.string.download_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun openFollowingSafeRedirects(request: DownloadRequest): HttpsURLConnection {
        var current = URL(request.url)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            if (!policy.isAllowed(current.toString())) {
                error("Blocked download redirect.")
            }

            val connection = current.openConnection() as? HttpsURLConnection
                ?: error("Only HTTPS downloads are supported.")
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.useCaches = false
            connection.setRequestProperty("Accept", "*/*")
            if (request.userAgent.isNotBlank()) {
                connection.setRequestProperty("User-Agent", request.userAgent)
            }
            if (policy.isInternal(current.toString()) && !request.internalCookie.isNullOrBlank()) {
                connection.setRequestProperty("Cookie", request.internalCookie)
            }

            val status = connection.responseCode
            if (status in HTTP_REDIRECTS) {
                if (redirectCount == MAX_REDIRECTS) {
                    connection.disconnect()
                    error("Too many download redirects.")
                }
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                current = current.toURI().resolve(location).toURL()
                return@repeat
            }

            if (status !in 200..299) {
                connection.errorStream?.close()
                connection.disconnect()
                error("Download failed.")
            }
            return connection
        }
        error("Download failed.")
    }

    private fun sanitizeFileName(rawName: String): String {
        val cleaned = rawName
            .substringAfterLast('/')
            .replace(INVALID_FILE_NAME_CHARS, "_")
            .trim()
            .take(MAX_FILE_NAME_LENGTH)
        return cleaned.ifBlank { "munitter-download" }
    }

    private fun urlWithoutQueryOrFragment(rawUrl: String): String {
        val uri = URI(rawUrl)
        return URI(
            uri.scheme,
            uri.authority,
            uri.path,
            null,
            null,
        ).toString()
    }

    companion object {
        private const val MAX_REDIRECTS = 5
        private const val MAX_FILE_NAME_LENGTH = 160
        private const val CONNECT_TIMEOUT_MILLIS = 30_000
        private const val READ_TIMEOUT_MILLIS = 120_000
        private val HTTP_REDIRECTS = setOf(
            HttpURLConnection.HTTP_MOVED_PERM,
            HttpURLConnection.HTTP_MOVED_TEMP,
            HttpURLConnection.HTTP_SEE_OTHER,
            307,
            308,
        )
        private val INVALID_FILE_NAME_CHARS = Regex("""[\\/:*?"<>|\u0000-\u001F]""")
    }
}

class SecureDownloadPolicy(
    internalHost: String,
) {
    private val internalHost = internalHost.lowercase()

    fun isAllowed(rawUrl: String?): Boolean {
        val uri = parse(rawUrl) ?: return false
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.userInfo == null &&
            uri.port in setOf(-1, 443) &&
            (
                host == internalHost ||
                    host == "media.munitter.com" ||
                    host.endsWith(".r2.cloudflarestorage.com")
                )
    }

    fun isInternal(rawUrl: String?): Boolean =
        parse(rawUrl)?.let {
            it.scheme.equals("https", ignoreCase = true) &&
                it.host.equals(internalHost, ignoreCase = true) &&
                it.port in setOf(-1, 443)
        } == true

    private fun parse(rawUrl: String?): URI? =
        runCatching { URI(rawUrl.orEmpty()) }.getOrNull()
}

private data class DownloadRequest(
    val url: String,
    val userAgent: String,
    val mimeType: String,
    val internalCookie: String?,
)

private data class DownloadDocumentTarget(
    val fileName: String,
    val mimeType: String,
)

private class CreateDownloadDocumentContract :
    ActivityResultContract<DownloadDocumentTarget, Uri?>() {
    override fun createIntent(
        context: Context,
        input: DownloadDocumentTarget,
    ): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = input.mimeType
        putExtra(Intent.EXTRA_TITLE, input.fileName)
    }

    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): Uri? = intent?.data.takeIf { resultCode == Activity.RESULT_OK }
}
