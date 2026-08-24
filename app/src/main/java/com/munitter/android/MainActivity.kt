package com.munitter.android

import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.SystemClock
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
import com.munitter.android.ui.StartupOverlayController
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
import androidx.lifecycle.lifecycleScope
import com.munitter.android.notification.MunitterNotificationCenter
import com.munitter.android.notification.FcmTokenRegistrar
import com.munitter.android.notification.FcmTokenStore
import com.munitter.android.notification.NotificationSyncEngine
import com.munitter.android.notification.NotificationSyncScheduler
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity(), MunitterWebViewClient.Callbacks {
    private var webView: WebView? = null
    private var munitterWebViewClient: MunitterWebViewClient? = null
    private var uiState by mutableStateOf(WebUiState())
    private var navigationHeaderSnapshot by mutableStateOf<Bitmap?>(null)
    private val startupOverlayController = StartupOverlayController(
        enabled = BuildConfig.ENABLE_STARTUP_OVERLAY,
    )
    private var startupOverlayVisible by mutableStateOf(startupOverlayController.isVisible)
    private var lastVisibleHeaderSnapshot: Bitmap? = null
    private lateinit var navigationPolicy: NavigationPolicy
    private lateinit var oauthState: OAuthNavigationState
    private lateinit var navigationCoordinator: NavigationCoordinator
    private lateinit var fileChooser: FileChooserCoordinator
    private lateinit var permissions: WebPermissionCoordinator
    private lateinit var fullscreen: FullscreenMediaController
    private lateinit var downloads: SecureDownloadCoordinator
    private lateinit var notificationCenter: MunitterNotificationCenter
    private var notificationSyncJob: Job? = null
    private var pendingNotificationPermissionUrl: String? = null
    private var pendingNotificationPermissionRequest: Runnable? = null
    private var activityCreatedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        activityCreatedAt = SystemClock.uptimeMillis()
        installSplashScreen()
        super.onCreate(savedInstanceState)
        android.util.Log.d(
            TAG,
            "Activity created startupOverlay=${startupOverlayController.isVisible}",
        )

        notificationCenter = MunitterNotificationCenter(this)
        NotificationSyncScheduler.schedule(this)
        initializeDevelopmentFcm()

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(
                if (BuildConfig.ENABLE_STARTUP_OVERLAY) Color.BLACK else Color.rgb(36, 33, 30),
            ),
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
            loadInternalUrl = { url -> loadUrlWithDebugHeaders(url) },
        )
        navigationCoordinator = NavigationCoordinator(
            policy = navigationPolicy,
            oauthState = oauthState,
            externalNavigator = externalNavigator,
            openInternalUrl = { url -> loadUrlWithDebugHeaders(url) },
        )
        fullscreen = FullscreenMediaController(this) { webView }

        if (candidate != null) {
            val client = MunitterWebViewClient(
                context = this,
                internalHost = BuildConfig.INTERNAL_HOST,
                navigationCoordinator = navigationCoordinator,
                oauthState = oauthState,
                startupPresentationEnabled = BuildConfig.ENABLE_STARTUP_OVERLAY,
                callbacks = this,
            )
            munitterWebViewClient = client
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
            if (startupOverlayController.onWebViewUnavailable()) {
                startupOverlayVisible = false
            }
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
                    navigationHeaderSnapshot = navigationHeaderSnapshot,
                    startupOverlayVisible = startupOverlayVisible,
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
                attemptDevelopmentDebugBootstrap(resolveLaunchUrl(intent?.dataString))
            } else if (BuildConfig.ENABLE_STARTUP_OVERLAY) {
                munitterWebViewClient?.observeRestoredState(candidate)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val requested = intent.dataString ?: return
        val decision = navigationPolicy.classify(requested, oauthState.isInProgress)
        if (decision.target == NavigationTarget.INTERNAL) {
            loadUrlWithDebugHeaders(decision.uri.toString())
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
        if (BuildConfig.ENVIRONMENT.equals("development", ignoreCase = true)) {
            lifecycleScope.launch { FcmTokenRegistrar(this@MainActivity).registerIfPossible() }
        }
        notificationSyncJob?.cancel()
        notificationSyncJob = lifecycleScope.launch {
            while (true) {
                NotificationSyncEngine(this@MainActivity).sync()
                delay(FOREGROUND_NOTIFICATION_SYNC_INTERVAL_MS)
            }
        }
    }

    override fun onPause() {
        notificationSyncJob?.cancel()
        notificationSyncJob = null
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
            pendingNotificationPermissionRequest?.let(activeWebView::removeCallbacks)
            (activeWebView.parent as? ViewGroup)?.removeView(activeWebView)
            activeWebView.stopLoading()
            activeWebView.webChromeClient = WebChromeClient()
            activeWebView.webViewClient = WebViewClient()
            activeWebView.destroy()
        }
        webView = null
        munitterWebViewClient = null
        pendingNotificationPermissionRequest = null
        pendingNotificationPermissionUrl = null
        super.onDestroy()
    }

    override fun onStartupNavigationStarted(generation: Long) {
        startupOverlayController.onNavigationStarted(generation)
        android.util.Log.d(
            TAG,
            "Startup navigation generation=$generation activityElapsedMs=${activityElapsedMs()}",
        )
    }

    override fun onStartupPresentationReady(webView: WebView, generation: Long) {
        if (!startupOverlayController.onPresentationReady(generation)) return
        startupOverlayVisible = false
        android.util.Log.d(
            TAG,
            "Startup overlay fade started generation=$generation activityElapsedMs=${activityElapsedMs()}",
        )
        schedulePendingNotificationPermission(webView)
    }

    override fun onStartupNavigationFailed(generation: Long?) {
        if (!startupOverlayController.onNavigationFailed(generation)) return
        startupOverlayVisible = false
        pendingNotificationPermissionRequest?.let { request -> webView?.removeCallbacks(request) }
        pendingNotificationPermissionRequest = null
        pendingNotificationPermissionUrl = null
        android.util.Log.d(
            TAG,
            "Startup overlay released for error generation=${generation ?: -1L} " +
                "activityElapsedMs=${activityElapsedMs()}",
        )
    }

    override fun onLoadingStarted(webView: WebView) {
        navigationHeaderSnapshot = if (uiState.hasVisibleContent) {
            lastVisibleHeaderSnapshot
        } else {
            null
        }
        uiState = uiState.copy(
            isLoading = true,
            progress = 0,
            failure = null,
        )
    }

    override fun onContentVisible(webView: WebView) {
        uiState = uiState.copy(
            isLoading = false,
            hasVisibleContent = true,
            failure = null,
        )
    }

    override fun onPageFinished(webView: WebView) {
        uiState = uiState.copy(
            isLoading = false,
            hasVisibleContent = true,
        )
        requestNotificationPermissionWhenStartupAllows(webView, webView.url)
        if (BuildConfig.ENVIRONMENT.equals("development", ignoreCase = true)) {
            lifecycleScope.launch { FcmTokenRegistrar(this@MainActivity).registerIfPossible() }
        }
    }

    private fun initializeDevelopmentFcm() {
        if (!BuildConfig.ENVIRONMENT.equals("development", ignoreCase = true)) return
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                android.util.Log.w(TAG, "FCM token retrieval failed", task.exception)
                return@addOnCompleteListener
            }
            val token = task.result?.trim().orEmpty()
            if (token.isBlank()) return@addOnCompleteListener
            FcmTokenStore(this).save(token)
            android.util.Log.i(TAG, "FCM token retrieved tokenHash=${token.hashCode().toUInt().toString(16)}")
            lifecycleScope.launch { FcmTokenRegistrar(this@MainActivity).registerIfPossible() }
        }
    }

    override fun onFailure(kind: WebFailureKind) {
        navigationHeaderSnapshot = null
        uiState = uiState.copy(
            isLoading = false,
            hasVisibleContent = false,
            failure = kind,
        )
    }

    override fun onRendererGone(webView: WebView) {
        navigationHeaderSnapshot = null
        lastVisibleHeaderSnapshot = null
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

    override fun onHeaderPresentationReady(webView: WebView) {
        navigationHeaderSnapshot = null
        webView.post {
            captureVisibleHeader(webView)?.let { lastVisibleHeaderSnapshot = it }
        }
    }

    private fun captureVisibleHeader(activeWebView: WebView): Bitmap? {
        if (activeWebView.width <= 0 || activeWebView.height <= 0 || !activeWebView.isShown) {
            return null
        }

        val headerHeight = (HEADER_SNAPSHOT_HEIGHT_DP * resources.displayMetrics.density)
            .toInt()
            .coerceIn(1, activeWebView.height)
        return runCatching {
            Bitmap.createBitmap(activeWebView.width, headerHeight, Bitmap.Config.ARGB_8888).also { bitmap ->
                activeWebView.draw(Canvas(bitmap))
            }
        }.getOrNull()
    }

    private fun activityElapsedMs(): Long =
        (SystemClock.uptimeMillis() - activityCreatedAt).coerceAtLeast(0L)

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

    private fun loadUrlWithDebugHeaders(rawUrl: String?) {
        val activeWebView = webView ?: return
        if (rawUrl.isNullOrBlank()) {
            return
        }

        val headers = debugClientHeaders()
        if (headers.isEmpty()) {
            activeWebView.loadUrl(rawUrl)
            return
        }

        activeWebView.loadUrl(rawUrl, headers)
    }

    private fun attemptDevelopmentDebugBootstrap(rawUrl: String?) {
        val targetUrl = rawUrl ?: BuildConfig.BASE_URL
        if (!canAttemptDevelopmentDebugBootstrap(targetUrl)) {
            loadUrlWithDebugHeaders(targetUrl)
            return
        }

        lifecycleScope.launch {
            val bootstrapped = runCatching { performDevelopmentDebugBootstrap(targetUrl) }.getOrDefault(false)
            if (!bootstrapped) {
                android.util.Log.w(
                    TAG,
                    "Development debug bootstrap failed; proceeding with normal login flow.",
                )
            }

            withContext(Dispatchers.Main) {
                loadUrlWithDebugHeaders(targetUrl)
            }
        }
    }

    private fun canAttemptDevelopmentDebugBootstrap(rawUrl: String): Boolean {
        if (!BuildConfig.DEBUG ||
            !BuildConfig.BUILD_TYPE.equals("debug", ignoreCase = true) ||
            !BuildConfig.ENVIRONMENT.equals("development", ignoreCase = true) ||
            BuildConfig.DEVELOPMENT_DEBUG_CLIENT_HEADER.isBlank()) {
            return false
        }

        val host = runCatching { android.net.Uri.parse(rawUrl).host }.getOrNull()
        return host != null && host.equals(BuildConfig.INTERNAL_HOST, ignoreCase = true)
    }

    private suspend fun performDevelopmentDebugBootstrap(baseUrl: String): Boolean =
        withContext(Dispatchers.IO) {
            val endpointBase = baseUrl.trimEnd('/')
            val bootstrapEndpoint = "$endpointBase/internal/dev-test-auth/bootstrap"

            val bootstrapResponse = postDevelopmentDebugRequest(
                bootstrapEndpoint,
            ) ?: return@withContext false

            if (bootstrapResponse.statusCode != HttpURLConnection.HTTP_OK) {
                return@withContext false
            }

            val hasSuccess = runCatching {
                JSONObject(bootstrapResponse.body).optBoolean("success", false)
            }.getOrDefault(false)
            if (!hasSuccess) {
                return@withContext false
            }

            applyBootstrapCookies(baseUrl, bootstrapResponse.setCookieHeaders)
        }

    private fun applyBootstrapCookies(baseUrl: String, setCookieHeaders: List<String>): Boolean {
        val cookieManager = CookieManager.getInstance()
        val base = baseUrl.trimEnd('/')
        var wroteCookie = false

        for (cookie in setCookieHeaders) {
            if (cookie.isBlank()) {
                continue
            }

            cookieManager.setCookie(base, cookie)
            wroteCookie = true
        }

        if (wroteCookie) {
            cookieManager.flush()
        }

        // A preserved authenticated session legitimately returns no Set-Cookie.
        // The bootstrap response itself is still successful in that case.
        return true
    }

    private fun postDevelopmentDebugRequest(
        endpointUrl: String,
    ): DevelopmentDebugBootstrapHttpResponse? {
        val connection = runCatching { URL(endpointUrl).openConnection() as HttpURLConnection }
            .getOrElse { return null }

        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = DEVELOPMENT_DEBUG_BOOTSTRAP_TIMEOUT_MS
            connection.readTimeout = DEVELOPMENT_DEBUG_BOOTSTRAP_TIMEOUT_MS
            connection.doOutput = false
            connection.setRequestProperty("Accept", "application/json")
            runCatching {
                CookieManager.getInstance().getCookie(BuildConfig.BASE_URL)
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let {
                connection.setRequestProperty("Cookie", it)
            }
            connection.setRequestProperty("X-Munitter-Client", BuildConfig.DEVELOPMENT_DEBUG_CLIENT_HEADER)

            val status = connection.responseCode
            val responseStream = if (status >= 200 && status < 300) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val responseBody = responseStream
                ?.let { BufferedInputStream(it) }
                ?.readBytes()
                ?.toString(Charsets.UTF_8)
                ?: ""
            DevelopmentDebugBootstrapHttpResponse(
                statusCode = status,
                body = responseBody,
                setCookieHeaders = connection.headerFields["Set-Cookie"]?.filterNotNull() ?: emptyList(),
            )
        } catch (ex: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun debugClientHeaders(): Map<String, String> {
        if (!BuildConfig.DEBUG || !BuildConfig.ENVIRONMENT.equals("development", ignoreCase = true)) {
            return emptyMap()
        }
        if (BuildConfig.DEVELOPMENT_DEBUG_CLIENT_HEADER.isBlank()) {
            return emptyMap()
        }

        return mapOf("X-Munitter-Client" to BuildConfig.DEVELOPMENT_DEBUG_CLIENT_HEADER)
    }

    private fun requestNotificationPermissionIfAppropriate(rawUrl: String?) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            notificationCenter.hasNotificationPermission() ||
            notificationCenter.permissionWasRequested() ||
            rawUrl.isNullOrBlank()) {
            return
        }

        val path = runCatching { android.net.Uri.parse(rawUrl).path.orEmpty() }.getOrDefault("")
        if (path.isBlank() || path == "/" || path in LOGIN_OR_PUBLIC_PATHS) {
            return
        }

        notificationCenter.markPermissionRequested()
        requestPermissions(
            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
            NOTIFICATION_PERMISSION_REQUEST_CODE,
        )
    }

    private fun requestNotificationPermissionWhenStartupAllows(webView: WebView, rawUrl: String?) {
        if (!BuildConfig.ENABLE_STARTUP_OVERLAY) {
            requestNotificationPermissionIfAppropriate(rawUrl)
            return
        }

        pendingNotificationPermissionUrl = rawUrl
        if (!startupOverlayVisible) {
            schedulePendingNotificationPermission(webView)
        }
    }

    private fun schedulePendingNotificationPermission(webView: WebView) {
        val rawUrl = pendingNotificationPermissionUrl ?: return
        pendingNotificationPermissionRequest?.let(webView::removeCallbacks)
        val request = Runnable {
            pendingNotificationPermissionRequest = null
            pendingNotificationPermissionUrl = null
            requestNotificationPermissionIfAppropriate(rawUrl)
        }
        pendingNotificationPermissionRequest = request
        webView.postDelayed(request, NOTIFICATION_PERMISSION_AFTER_STARTUP_DELAY_MS)
    }

    companion object {
        private const val STATE_WEBVIEW = "munitter.webview.state"
        private const val STATE_OAUTH_IN_PROGRESS = "munitter.oauth.in_progress"
        private const val DEVELOPMENT_DEBUG_BOOTSTRAP_TIMEOUT_MS = 8_000
        private const val HEADER_SNAPSHOT_HEIGHT_DP = 56
        private const val FOREGROUND_NOTIFICATION_SYNC_INTERVAL_MS = 60_000L
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 4201
        private const val NOTIFICATION_PERMISSION_AFTER_STARTUP_DELAY_MS = 250L
        private val LOGIN_OR_PUBLIC_PATHS = setOf(
            "/",
            "/index",
            "/login",
            "/email/login",
            "/email/register",
            "/password/forgot",
            "/terms",
            "/privacy",
            "/contact",
        )
        private const val TAG = "MainActivity"
    }

    private data class DevelopmentDebugBootstrapHttpResponse(
        val statusCode: Int,
        val body: String,
        val setCookieHeaders: List<String>,
    )
}
