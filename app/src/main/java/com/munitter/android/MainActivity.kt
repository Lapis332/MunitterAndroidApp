package com.munitter.android

import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.munitter.android.download.SecureDownloadCoordinator
import com.munitter.android.media.FileChooserCoordinator
import com.munitter.android.navigation.BackNavigationDecider
import com.munitter.android.navigation.BackNavigationDecision
import com.munitter.android.navigation.ExternalNavigator
import com.munitter.android.navigation.NavigationCoordinator
import com.munitter.android.navigation.NavigationPolicy
import com.munitter.android.navigation.NavigationTarget
import com.munitter.android.navigation.OAuthNavigationState
import com.munitter.android.ui.MunitterScreen
import com.munitter.android.ui.MunitterTheme
import com.munitter.android.web.FullscreenMediaController
import com.munitter.android.web.MunitterWebChromeClient
import com.munitter.android.web.MunitterWebViewClient
import com.munitter.android.web.WebFailureKind
import com.munitter.android.web.WebPermissionCoordinator
import com.munitter.android.web.WebUiState
import com.munitter.android.web.WebViewConfigurator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity(), MunitterWebViewClient.Callbacks {
    private var webView: WebView? = null
    private var uiState by mutableStateOf(WebUiState())
    private lateinit var navigationPolicy: NavigationPolicy
    private lateinit var oauthState: OAuthNavigationState
    private lateinit var navigationCoordinator: NavigationCoordinator
    private lateinit var fileChooser: FileChooserCoordinator
    private lateinit var permissions: WebPermissionCoordinator
    private lateinit var fullscreen: FullscreenMediaController
    private lateinit var downloads: SecureDownloadCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.rgb(36, 33, 30)),
        )

        navigationPolicy = NavigationPolicy(BuildConfig.INTERNAL_HOST)
        oauthState = OAuthNavigationState(
            initialValue = savedInstanceState?.getBoolean(STATE_OAUTH_IN_PROGRESS) == true,
        )
        fileChooser = FileChooserCoordinator(this)
        permissions = WebPermissionCoordinator(this, navigationPolicy)
        downloads = SecureDownloadCoordinator(this, BuildConfig.INTERNAL_HOST)

        val candidate = runCatching { WebView(this) }.getOrNull()
        webView = candidate

        val externalNavigator = ExternalNavigator(
            activity = this,
            navigationPolicy = navigationPolicy,
            loadInternalUrl = { url -> webView?.loadUrl(url) },
        )
        navigationCoordinator = NavigationCoordinator(
            policy = navigationPolicy,
            oauthState = oauthState,
            externalNavigator = externalNavigator,
            webViewProvider = { webView },
        )
        fullscreen = FullscreenMediaController(this) { webView }

        if (candidate != null) {
            val client = MunitterWebViewClient(
                context = this,
                internalHost = BuildConfig.INTERNAL_HOST,
                navigationCoordinator = navigationCoordinator,
                oauthState = oauthState,
                callbacks = this,
            )
            val chromeClient = MunitterWebChromeClient(
                fileChooser = fileChooser,
                permissions = permissions,
                fullscreen = fullscreen,
                navigationCoordinator = navigationCoordinator,
                onProgressChanged = ::updateProgress,
            )
            WebViewConfigurator.configure(
                webView = candidate,
                webViewClient = client,
                webChromeClient = chromeClient,
                onDownload = downloads::requestDownload,
            )
        } else {
            uiState = WebUiState(
                isLoading = false,
                failure = WebFailureKind.WEBVIEW_UNAVAILABLE,
            )
        }

        setContent {
            MunitterTheme {
                MunitterScreen(
                    webView = webView,
                    state = uiState,
                    onRetry = ::retry,
                    onBack = ::handleBack,
                )
            }
        }

        if (candidate != null) {
            val restored = savedInstanceState
                ?.getBundle(STATE_WEBVIEW)
                ?.let(candidate::restoreState) != null
            if (!restored) {
                candidate.loadUrl(resolveLaunchUrl(intent?.dataString))
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val requested = intent.dataString ?: return
        val decision = navigationPolicy.classify(requested, oauthState.isInProgress)
        if (decision.target == NavigationTarget.INTERNAL) {
            webView?.loadUrl(decision.uri.toString())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView?.let { activeWebView ->
            val webState = Bundle()
            activeWebView.saveState(webState)
            outState.putBundle(STATE_WEBVIEW, webState)
        }
        outState.putBoolean(STATE_OAUTH_IN_PROGRESS, oauthState.isInProgress)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        webView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (::fullscreen.isInitialized) {
            fullscreen.hide()
        }
        if (::fileChooser.isInitialized) {
            fileChooser.cancelPending()
        }
        if (::permissions.isInitialized) {
            permissions.cancelPending()
        }

        webView?.let { activeWebView ->
            (activeWebView.parent as? ViewGroup)?.removeView(activeWebView)
            activeWebView.stopLoading()
            activeWebView.webChromeClient = WebChromeClient()
            activeWebView.webViewClient = WebViewClient()
            activeWebView.destroy()
        }
        webView = null
        super.onDestroy()
    }

    override fun onLoadingStarted() {
        uiState = uiState.copy(
            isLoading = true,
            progress = 0,
            failure = null,
        )
    }

    override fun onContentVisible() {
        uiState = uiState.copy(
            isLoading = false,
            hasVisibleContent = true,
            failure = null,
        )
    }

    override fun onPageFinished() {
        uiState = uiState.copy(
            isLoading = false,
            hasVisibleContent = true,
        )
    }

    override fun onFailure(kind: WebFailureKind) {
        uiState = uiState.copy(
            isLoading = false,
            hasVisibleContent = false,
            failure = kind,
        )
    }

    override fun onRendererGone(webView: WebView) {
        (webView.parent as? ViewGroup)?.removeView(webView)
        if (this.webView === webView) {
            this.webView = null
        }
        webView.destroy()
        uiState = WebUiState(
            isLoading = false,
            failure = WebFailureKind.GENERIC,
        )
        recreate()
    }

    private fun updateProgress(progress: Int) {
        uiState = uiState.copy(
            progress = progress,
            isLoading = progress < 100 && !uiState.hasVisibleContent,
        )
    }

    private fun retry() {
        val activeWebView = webView
        if (activeWebView == null) {
            recreate()
            return
        }
        uiState = WebUiState(isLoading = true)
        activeWebView.reload()
    }

    private fun handleBack() {
        when (
            BackNavigationDecider.decide(
                isFullscreenMedia = fullscreen.isShowing,
                canWebViewGoBack = webView?.canGoBack() == true,
            )
        ) {
            BackNavigationDecision.CLOSE_FULLSCREEN -> fullscreen.hide()
            BackNavigationDecision.WEBVIEW_BACK -> webView?.goBack()
            BackNavigationDecision.FINISH_ACTIVITY -> finishAfterTransition()
        }
    }

    private fun resolveLaunchUrl(rawUrl: String?): String {
        val decision = navigationPolicy.classify(rawUrl, oauthInProgress = false)
        return if (decision.target == NavigationTarget.INTERNAL) {
            decision.uri.toString()
        } else {
            BuildConfig.BASE_URL
        }
    }

    companion object {
        private const val STATE_WEBVIEW = "munitter.webview.state"
        private const val STATE_OAUTH_IN_PROGRESS = "munitter.oauth.in_progress"
    }
}
