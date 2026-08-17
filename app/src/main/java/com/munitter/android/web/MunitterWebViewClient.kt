package com.munitter.android.web

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.http.SslError
import android.os.SystemClock
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.SafeBrowsingResponseCompat
import androidx.webkit.WebResourceErrorCompat
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewFeature
import com.munitter.android.navigation.NavigationCoordinator
import com.munitter.android.navigation.OAuthNavigationState
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream

class MunitterWebViewClient(
    private val context: Context,
    private val internalHost: String,
    private val navigationCoordinator: NavigationCoordinator,
    private val oauthState: OAuthNavigationState,
    private val callbacks: Callbacks,
) : WebViewClientCompat() {
    private var mainFrameFailed = false
    private var activeMainFrameUrl: String? = null
    private var headerProbeGeneration = 0
    private var headerPresentedGeneration = -1
    private var lastPresentedAvatarOwner = ""
    private var lastPresentedAvatarSource = ""
    private var navigationStartedAt = 0L

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean {
        if (!request.isForMainFrame) return false
        return navigationCoordinator.shouldOverrideMainFrame(
            rawUrl = request.url.toString(),
            hasUserGesture = request.hasGesture(),
        )
    }

    @Deprecated("Used only by old WebView implementations")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
        navigationCoordinator.shouldOverrideMainFrame(
            rawUrl = url,
            hasUserGesture = false,
        )

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        if (
            request.isForMainFrame &&
            !navigationCoordinator.allowsMainFrameNetworkRequest(request.url.toString())
        ) {
            return WebResourceResponse(
                "text/plain",
                "UTF-8",
                403,
                "Blocked",
                mapOf("Cache-Control" to "no-store"),
                ByteArrayInputStream(ByteArray(0)),
            )
        }
        return super.shouldInterceptRequest(view, request)
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
        headerProbeGeneration += 1
        navigationStartedAt = SystemClock.uptimeMillis()
        activeMainFrameUrl = url
        mainFrameFailed = false
        if (!navigationCoordinator.allowsMainFrameNetworkRequest(url)) {
            view.stopLoading()
            mainFrameFailed = true
            callbacks.onFailure(WebFailureKind.SECURITY)
            return
        }
        callbacks.onLoadingStarted(view)
    }

    override fun onPageCommitVisible(view: WebView, url: String) {
        if (!mainFrameFailed) {
            Log.d(TAG, "Page commit visible generation=$headerProbeGeneration elapsedMs=${elapsedNavigationMs()}")
            callbacks.onContentVisible(view)
            probeHeaderPresentation(
                view = view,
                generation = headerProbeGeneration,
                deadline = SystemClock.uptimeMillis() + HEADER_PRESENTATION_FAILSAFE_MS,
            )
        }
    }

    override fun onPageFinished(view: WebView, url: String?) {
        android.webkit.CookieManager.getInstance().flush()
        val uri = runCatching { java.net.URI(url.orEmpty()) }.getOrNull()
        oauthState.recordPageFinished(uri, internalHost)
        callbacks.onPageFinished(view)
        probeHeaderPresentation(
            view = view,
            generation = headerProbeGeneration,
            deadline = SystemClock.uptimeMillis() + HEADER_PRESENTATION_FAILSAFE_MS,
        )
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceErrorCompat,
    ) {
        if (!request.isForMainFrame) return
        mainFrameFailed = true
        val errorCode = if (
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_RESOURCE_ERROR_GET_CODE)
        ) {
            error.errorCode
        } else {
            android.webkit.WebViewClient.ERROR_UNKNOWN
        }
        callbacks.onFailure(
            WebFailureClassifier.fromWebViewError(
                errorCode = errorCode,
                isOnline = isOnline(),
            ),
        )
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse,
    ) {
        if (request.isForMainFrame && errorResponse.statusCode >= 500) {
            mainFrameFailed = true
            callbacks.onFailure(WebFailureKind.SERVER)
        }
    }

    override fun onReceivedSslError(
        view: WebView,
        handler: SslErrorHandler,
        error: SslError,
    ) {
        handler.cancel()
        if (isActiveMainFrameUrl(error.url, view.url)) {
            mainFrameFailed = true
            callbacks.onFailure(WebFailureKind.TLS)
        }
    }

    override fun onSafeBrowsingHit(
        view: WebView,
        request: WebResourceRequest,
        threatType: Int,
        callback: SafeBrowsingResponseCompat,
    ) {
        mainFrameFailed = true
        if (
            WebViewFeature.isFeatureSupported(
                WebViewFeature.SAFE_BROWSING_RESPONSE_BACK_TO_SAFETY,
            )
        ) {
            callback.backToSafety(true)
        } else {
            super.onSafeBrowsingHit(view, request, threatType, callback)
        }
        callbacks.onFailure(WebFailureKind.SECURITY)
    }

    override fun onRenderProcessGone(
        view: WebView,
        detail: RenderProcessGoneDetail,
    ): Boolean {
        callbacks.onRendererGone(view)
        return true
    }

    private fun isOnline(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun probeHeaderPresentation(
        view: WebView,
        generation: Int,
        deadline: Long,
    ) {
        if (generation != headerProbeGeneration || generation == headerPresentedGeneration || mainFrameFailed) return

        view.evaluateJavascript(HEADER_PRESENTATION_SCRIPT) { rawResult ->
            if (generation != headerProbeGeneration || generation == headerPresentedGeneration || mainFrameFailed) return@evaluateJavascript

            val result = parseHeaderPresentation(rawResult)
            val ownerChanged = lastPresentedAvatarOwner.isNotEmpty() &&
                result.owner.isNotEmpty() &&
                result.owner != lastPresentedAvatarOwner
            if (HeaderPresentationReleasePolicy.shouldRelease(
                    previousOwner = lastPresentedAvatarOwner,
                    currentOwner = result.owner,
                    state = result.state,
                    timedOut = false,
                )) {
                if (result.state == STATE_READY || result.state == STATE_FORMAL_FALLBACK) {
                    lastPresentedAvatarOwner = result.owner
                    lastPresentedAvatarSource = result.source
                } else if (ownerChanged) {
                    lastPresentedAvatarOwner = result.owner
                    lastPresentedAvatarSource = result.source
                }
                headerPresentedGeneration = generation
                Log.d(
                    TAG,
                    "Header presentation ready generation=$generation state=${result.state} " +
                        "ownerChanged=$ownerChanged elapsedMs=${elapsedNavigationMs()}",
                )
                callbacks.onHeaderPresentationReady(view)
                return@evaluateJavascript
            }

            if (HeaderPresentationReleasePolicy.shouldRelease(
                    previousOwner = lastPresentedAvatarOwner,
                    currentOwner = result.owner,
                    state = result.state,
                    timedOut = SystemClock.uptimeMillis() >= deadline,
                )) {
                Log.w(TAG, "Header avatar readiness failsafe released generation=$generation state=${result.state}")
                headerPresentedGeneration = generation
                callbacks.onHeaderPresentationReady(view)
                return@evaluateJavascript
            }

            view.postDelayed(
                { probeHeaderPresentation(view, generation, deadline) },
                HEADER_PRESENTATION_POLL_MS,
            )
        }
    }

    private fun parseHeaderPresentation(rawResult: String?): HeaderPresentationResult =
        runCatching {
            val jsonText = JSONTokener(rawResult.orEmpty()).nextValue() as? String ?: "{}"
            val json = JSONObject(jsonText)
            HeaderPresentationResult(
                state = json.optString("state", STATE_PENDING),
                owner = json.optString("owner", ""),
                source = json.optString("source", ""),
            )
        }.getOrDefault(HeaderPresentationResult())

    private fun elapsedNavigationMs(): Long =
        (SystemClock.uptimeMillis() - navigationStartedAt).coerceAtLeast(0L)

    private fun isActiveMainFrameUrl(
        failingUrl: String?,
        currentWebViewUrl: String?,
    ): Boolean {
        val normalizedFailure = normalizeUrl(failingUrl) ?: return false
        return normalizedFailure == normalizeUrl(activeMainFrameUrl) ||
            normalizedFailure == normalizeUrl(currentWebViewUrl)
    }

    private fun normalizeUrl(rawUrl: String?): String? =
        runCatching {
            val uri = java.net.URI(rawUrl.orEmpty())
            java.net.URI(
                uri.scheme,
                uri.authority,
                uri.path,
                uri.query,
                null,
            ).normalize().toString()
        }.getOrNull()

    interface Callbacks {
        fun onLoadingStarted(webView: WebView)
        fun onContentVisible(webView: WebView)
        fun onPageFinished(webView: WebView)
        fun onHeaderPresentationReady(webView: WebView)
        fun onFailure(kind: WebFailureKind)
        fun onRendererGone(webView: WebView)
    }


    private data class HeaderPresentationResult(
        val state: String = STATE_PENDING,
        val owner: String = "",
        val source: String = "",
    )

    companion object {
        private const val TAG = "MunitterWebViewClient"
        private const val HEADER_PRESENTATION_POLL_MS = 50L
        private const val HEADER_PRESENTATION_FAILSAFE_MS = 5_000L
        private const val STATE_PENDING = "pending"
        private const val STATE_READY = "ready"
        private const val STATE_FORMAL_FALLBACK = "formal-fallback"
        private val HEADER_PRESENTATION_SCRIPT = """
            (() => {
              const header = document.querySelector('[data-primary-page-header]');
              if (!header) return JSON.stringify({ state: 'pending' });
              const avatar = header.querySelector('[data-primary-header-avatar]');
              if (!avatar) return JSON.stringify({ state: 'no-avatar' });
              const owner = avatar.getAttribute('data-avatar-owner-id') || '';
              const source = avatar.getAttribute('data-avatar-image-id') || '';
              const image = avatar.querySelector('img[data-avatar-image]');
              if (!image || !source || image.dataset.avatarLoadFailed === 'true') {
                return JSON.stringify({ state: 'formal-fallback', owner, source });
              }
              if (image.complete && image.naturalWidth > 0 && image.dataset.avatarDecodeStarted !== 'true') {
                image.dataset.avatarDecodeStarted = 'true';
                image.decode().then(() => requestAnimationFrame(() => {
                  image.dataset.avatarPresentationReady = 'true';
                })).catch(() => {
                  image.dataset.avatarDecodeFailed = 'true';
                });
              }
              const visible = image.dataset.avatarPresentationReady === 'true'
                && getComputedStyle(image).visibility !== 'hidden'
                && image.getClientRects().length > 0;
              return JSON.stringify({ state: visible ? 'ready' : 'pending', owner, source });
            })()
        """.trimIndent()
    }
}
