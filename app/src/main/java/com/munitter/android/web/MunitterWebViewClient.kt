package com.munitter.android.web

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.http.SslError
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
        activeMainFrameUrl = url
        mainFrameFailed = false
        if (!navigationCoordinator.allowsMainFrameNetworkRequest(url)) {
            view.stopLoading()
            mainFrameFailed = true
            callbacks.onFailure(WebFailureKind.SECURITY)
            return
        }
        callbacks.onLoadingStarted()
    }

    override fun onPageCommitVisible(view: WebView, url: String) {
        if (!mainFrameFailed) {
            callbacks.onContentVisible()
        }
    }

    override fun onPageFinished(view: WebView, url: String?) {
        android.webkit.CookieManager.getInstance().flush()
        val uri = runCatching { java.net.URI(url.orEmpty()) }.getOrNull()
        oauthState.recordPageFinished(uri, internalHost)
        callbacks.onPageFinished()
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
        fun onLoadingStarted()
        fun onContentVisible()
        fun onPageFinished()
        fun onFailure(kind: WebFailureKind)
        fun onRendererGone(webView: WebView)
    }
}
