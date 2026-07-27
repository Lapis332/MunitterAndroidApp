package com.munitter.android.web

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.munitter.android.BuildConfig

object WebViewConfigurator {
    @SuppressLint("SetJavaScriptEnabled")
    fun configure(
        webView: WebView,
        webViewClient: MunitterWebViewClient,
        webChromeClient: MunitterWebChromeClient,
        onDownload: (
            url: String?,
            userAgent: String?,
            contentDisposition: String?,
            mimeType: String?,
        ) -> Unit,
    ) {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.WEBVIEW_DEBUGGABLE)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, BuildConfig.ACCEPT_THIRD_PARTY_COOKIES)
        }

        webView.setBackgroundColor(Color.rgb(36, 33, 30))
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)
            setGeolocationEnabled(false)
            displayZoomControls = false
            userAgentString = "$userAgentString ${BuildConfig.APP_UA_TOKEN}"
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(webView.settings, true)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, false)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) {
            val serviceWorkerSettings =
                ServiceWorkerControllerCompat.getInstance().serviceWorkerWebSettings
            if (
                WebViewFeature.isFeatureSupported(
                    WebViewFeature.SERVICE_WORKER_CONTENT_ACCESS,
                )
            ) {
                serviceWorkerSettings.allowContentAccess = false
            }
            if (
                WebViewFeature.isFeatureSupported(
                    WebViewFeature.SERVICE_WORKER_FILE_ACCESS,
                )
            ) {
                serviceWorkerSettings.allowFileAccess = false
            }
        }

        webView.webViewClient = webViewClient
        webView.webChromeClient = webChromeClient
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            onDownload(url, userAgent, contentDisposition, mimeType)
        }
    }
}
