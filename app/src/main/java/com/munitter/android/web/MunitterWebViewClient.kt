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
    private val startupPresentationEnabled: Boolean,
    private val callbacks: Callbacks,
) : WebViewClientCompat() {
    private var mainFrameFailed = false
    private var activeMainFrameUrl: String? = null
    private var headerProbeGeneration = 0
    private var headerPresentedGeneration = -1
    private var lastPresentedAvatarOwner = ""
    private var lastPresentedAvatarSource = ""
    private var navigationStartedAt = 0L
    private var navigationGeneration = 0L
    private var activeNavigationGeneration = 0L
    private val navigationGenerationsByUrl = linkedMapOf<String, Long>()
    private var startupProbeGeneration = -1L
    private var startupProbeAllowsDocumentFallback = false
    private var startupProbeInFlight = false
    private var startupVisualStateGeneration = -1L

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
        if (startupPresentationEnabled) {
            navigationGeneration += 1
            activeNavigationGeneration = navigationGeneration
            rememberNavigationGeneration(url, navigationGeneration)
            startupProbeGeneration = navigationGeneration
            startupProbeAllowsDocumentFallback = false
            startupProbeInFlight = false
            startupVisualStateGeneration = -1L
            callbacks.onStartupNavigationStarted(navigationGeneration)
            Log.d(
                TAG,
                "Page started generation=$navigationGeneration activityElapsedMs=${elapsedNavigationMs()}",
            )
        }
        if (!navigationCoordinator.allowsMainFrameNetworkRequest(url)) {
            view.stopLoading()
            mainFrameFailed = true
            if (startupPresentationEnabled) {
                callbacks.onStartupNavigationFailed(navigationGeneration)
            }
            callbacks.onFailure(WebFailureKind.SECURITY)
            return
        }
        callbacks.onLoadingStarted(view)
    }

    override fun onPageCommitVisible(view: WebView, url: String) {
        val generation = generationForUrl(url) ?: activeNavigationGeneration
        if (
            !mainFrameFailed &&
            (!startupPresentationEnabled || generation == activeNavigationGeneration)
        ) {
            Log.d(TAG, "Page commit visible generation=$generation elapsedMs=${elapsedNavigationMs()}")
            callbacks.onContentVisible(view)
            requestStartupPresentation(
                view = view,
                generation = generation,
                allowDocumentFallback = false,
            )
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
        val generation = generationForUrl(url) ?: activeNavigationGeneration
        Log.d(TAG, "Page finished generation=$generation elapsedMs=${elapsedNavigationMs()}")
        if (!startupPresentationEnabled || generation == activeNavigationGeneration) {
            callbacks.onPageFinished(view)
            requestStartupPresentation(
                view = view,
                generation = generation,
                allowDocumentFallback = true,
            )
        }
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
        val generation = generationForUrl(request.url.toString()) ?: activeNavigationGeneration
        if (generation != activeNavigationGeneration) return
        mainFrameFailed = true
        val errorCode = if (
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_RESOURCE_ERROR_GET_CODE)
        ) {
            error.errorCode
        } else {
            android.webkit.WebViewClient.ERROR_UNKNOWN
        }
        if (startupPresentationEnabled) {
            callbacks.onStartupNavigationFailed(generation)
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
            val generation = generationForUrl(request.url.toString()) ?: activeNavigationGeneration
            if (generation != activeNavigationGeneration) return
            mainFrameFailed = true
            if (startupPresentationEnabled) {
                callbacks.onStartupNavigationFailed(generation)
            }
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
            if (startupPresentationEnabled) {
                callbacks.onStartupNavigationFailed(activeNavigationGeneration.takeIf { it > 0L })
            }
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
        if (startupPresentationEnabled) {
            callbacks.onStartupNavigationFailed(
                generationForUrl(request.url.toString())
                    ?: activeNavigationGeneration.takeIf { it > 0L },
            )
        }
        callbacks.onFailure(WebFailureKind.SECURITY)
    }

    override fun onRenderProcessGone(
        view: WebView,
        detail: RenderProcessGoneDetail,
    ): Boolean {
        if (startupPresentationEnabled) {
            callbacks.onStartupNavigationFailed(activeNavigationGeneration.takeIf { it > 0L })
        }
        callbacks.onRendererGone(view)
        return true
    }

    fun observeRestoredState(view: WebView) {
        if (!startupPresentationEnabled) return
        view.post {
            if (activeNavigationGeneration == 0L) {
                navigationGeneration += 1
                activeNavigationGeneration = navigationGeneration
                activeMainFrameUrl = view.url
                rememberNavigationGeneration(view.url, navigationGeneration)
                startupProbeGeneration = navigationGeneration
                startupProbeAllowsDocumentFallback = true
                startupProbeInFlight = false
                startupVisualStateGeneration = -1L
                navigationStartedAt = SystemClock.uptimeMillis()
                callbacks.onStartupNavigationStarted(navigationGeneration)
                callbacks.onLoadingStarted(view)
                Log.d(TAG, "Restored WebView observation generation=$navigationGeneration")
            }
            requestStartupPresentation(
                view = view,
                generation = activeNavigationGeneration,
                allowDocumentFallback = true,
            )
        }
    }

    private fun isOnline(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun requestStartupPresentation(
        view: WebView,
        generation: Long,
        allowDocumentFallback: Boolean,
    ) {
        if (!startupPresentationEnabled) return
        if (
            generation <= 0L ||
            generation != activeNavigationGeneration ||
            mainFrameFailed ||
            generation == startupVisualStateGeneration
        ) {
            return
        }

        if (startupProbeGeneration != generation) {
            startupProbeGeneration = generation
            startupProbeAllowsDocumentFallback = allowDocumentFallback
            startupProbeInFlight = false
        } else if (allowDocumentFallback) {
            startupProbeAllowsDocumentFallback = true
        }
        if (startupProbeInFlight) return

        startupProbeInFlight = true
        val script = STARTUP_PRESENTATION_SCRIPT
            .replace("__NAVIGATION_GENERATION__", generation.toString())
            .replace(
                "__ALLOW_DOCUMENT_FALLBACK__",
                startupProbeAllowsDocumentFallback.toString(),
            )
        view.evaluateJavascript(script) { rawResult ->
            startupProbeInFlight = false
            if (
                generation != activeNavigationGeneration ||
                mainFrameFailed ||
                generation == startupVisualStateGeneration
            ) {
                return@evaluateJavascript
            }

            if (parseStartupPresentation(rawResult) == STARTUP_STATE_READY) {
                awaitStartupVisualState(view, generation)
                return@evaluateJavascript
            }

            view.postDelayed(
                {
                    requestStartupPresentation(
                        view = view,
                        generation = generation,
                        allowDocumentFallback = startupProbeAllowsDocumentFallback,
                    )
                },
                STARTUP_PRESENTATION_POLL_MS,
            )
        }
    }

    private fun awaitStartupVisualState(view: WebView, generation: Long) {
        if (
            generation != activeNavigationGeneration ||
            mainFrameFailed ||
            startupVisualStateGeneration == generation
        ) {
            return
        }
        startupVisualStateGeneration = generation
        Log.d(TAG, "Startup DOM presentation ready generation=$generation elapsedMs=${elapsedNavigationMs()}")
        view.postVisualStateCallback(
            generation,
            object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    if (
                        requestId != activeNavigationGeneration ||
                        mainFrameFailed ||
                        requestId != startupVisualStateGeneration
                    ) {
                        return
                    }
                    awaitStartupCompositorFrames(
                        view = view,
                        generation = requestId,
                        remainingFrames = STARTUP_COMPOSITOR_SETTLE_FRAMES,
                    )
                }
            },
        )
    }

    private fun awaitStartupCompositorFrames(
        view: WebView,
        generation: Long,
        remainingFrames: Int,
    ) {
        view.postOnAnimation {
            if (
                generation != activeNavigationGeneration ||
                mainFrameFailed ||
                generation != startupVisualStateGeneration
            ) {
                return@postOnAnimation
            }
            if (remainingFrames > 1) {
                awaitStartupCompositorFrames(view, generation, remainingFrames - 1)
                return@postOnAnimation
            }
            Log.d(
                TAG,
                "Startup visual state ready generation=$generation elapsedMs=${elapsedNavigationMs()}",
            )
            callbacks.onStartupPresentationReady(view, generation)
        }
    }

    private fun parseStartupPresentation(rawResult: String?): String =
        runCatching {
            JSONTokener(rawResult.orEmpty()).nextValue() as? String
        }.getOrNull() ?: STARTUP_STATE_PENDING

    private fun rememberNavigationGeneration(rawUrl: String?, generation: Long) {
        val normalized = normalizeUrl(rawUrl) ?: return
        navigationGenerationsByUrl[normalized] = generation
        while (navigationGenerationsByUrl.size > MAX_TRACKED_NAVIGATION_URLS) {
            navigationGenerationsByUrl.remove(navigationGenerationsByUrl.keys.first())
        }
    }

    private fun generationForUrl(rawUrl: String?): Long? =
        normalizeUrl(rawUrl)?.let(navigationGenerationsByUrl::get)

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
        fun onStartupNavigationStarted(generation: Long)
        fun onStartupPresentationReady(webView: WebView, generation: Long)
        fun onStartupNavigationFailed(generation: Long?)
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
        private const val STARTUP_PRESENTATION_POLL_MS = 50L
        private const val STARTUP_COMPOSITOR_SETTLE_FRAMES = 3
        private const val STARTUP_STATE_PENDING = "pending"
        private const val STARTUP_STATE_READY = "ready"
        private const val MAX_TRACKED_NAVIGATION_URLS = 8
        private const val HEADER_PRESENTATION_POLL_MS = 50L
        private const val HEADER_PRESENTATION_FAILSAFE_MS = 5_000L
        private const val STATE_PENDING = "pending"
        private const val STATE_READY = "ready"
        private const val STATE_FORMAL_FALLBACK = "formal-fallback"
        private val STARTUP_PRESENTATION_SCRIPT = """
            (() => {
              const generation = '__NAVIGATION_GENERATION__';
              const allowDocumentFallback = __ALLOW_DOCUMENT_FALLBACK__;
              const root = document.documentElement;
              const body = document.body;
              if (!root || !body) return 'pending';

              const viewportWidth = Math.max(root.clientWidth, window.innerWidth || 0);
              const viewportHeight = Math.max(root.clientHeight, window.innerHeight || 0);
              const isVisible = element => {
                if (!(element instanceof Element)) return false;
                const rect = element.getBoundingClientRect();
                if (rect.width < 1 || rect.height < 1) return false;
                if (rect.bottom <= 0 || rect.right <= 0 || rect.top >= viewportHeight || rect.left >= viewportWidth) {
                  return false;
                }
                for (let current = element; current && current !== root; current = current.parentElement) {
                  const style = getComputedStyle(current);
                  if (style.display === 'none' || style.visibility === 'hidden' || Number(style.opacity) === 0) {
                    return false;
                  }
                }
                return true;
              };
              const isStartupChrome = element => element.closest(
                'nav, [role="navigation"], footer, .bottom-nav, [hidden], [aria-hidden="true"]'
              ) !== null;
              const isInPrimaryViewport = element => {
                if (!isVisible(element) || isStartupChrome(element)) return false;
                const rect = element.getBoundingClientRect();
                return rect.top < viewportHeight * 0.82 && rect.bottom > 0;
              };
              const hasPaintedText = scope => {
                const walker = document.createTreeWalker(scope, NodeFilter.SHOW_TEXT);
                let paintedCharacters = 0;
                let paintedRuns = 0;
                for (let node = walker.nextNode(); node; node = walker.nextNode()) {
                  const text = (node.nodeValue || '').replace(/\s+/g, ' ').trim();
                  const owner = node.parentElement;
                  if (!text || !owner || !isInPrimaryViewport(owner)) continue;
                  const range = document.createRange();
                  range.selectNodeContents(node);
                  const rect = range.getBoundingClientRect();
                  if (rect.width < 1 || rect.height < 1 || rect.top >= viewportHeight * 0.82 || rect.bottom <= 0) {
                    continue;
                  }
                  paintedCharacters += text.length;
                  paintedRuns += 1;
                  if (paintedCharacters >= 8 && paintedRuns >= 2) return true;
                }
                return paintedCharacters >= 16;
              };
              const hasDecodedVisual = scope => Array.from(
                scope.querySelectorAll('img[src], canvas, video, svg')
              ).some(element => {
                if (!isInPrimaryViewport(element)) return false;
                if (element instanceof HTMLImageElement) {
                  return element.complete && element.naturalWidth > 0 && element.naturalHeight > 0;
                }
                if (element instanceof HTMLVideoElement) return element.readyState >= 2;
                return true;
              });
              const hasReadyForm = scope => Array.from(scope.querySelectorAll('form')).some(form => {
                if (!isInPrimaryViewport(form)) return false;
                const controls = Array.from(form.querySelectorAll('input, button, select, textarea'))
                  .filter(isInPrimaryViewport);
                return controls.length >= 2;
              });
              const hasRenderedSurface = scope => isVisible(scope) &&
                (hasPaintedText(scope) || hasDecodedVisual(scope) || hasReadyForm(scope));

              const home = document.querySelector('[data-home-presentation-state]');
              let primaryReady = false;
              if (home) {
                const activePanel = home.querySelector(
                  '[data-home-tab-panel].active, [data-home-tab-panel][aria-hidden="false"]'
                );
                const firstSurface = activePanel?.querySelector(
                  '.post-card, .home-groups-hero:not([hidden]), [data-home-initial-error]'
                );
                primaryReady = home.getAttribute('data-home-presentation-state') === 'ready' &&
                  firstSurface instanceof Element && hasRenderedSurface(firstSurface);
              } else {
                const primaryScope = document.querySelector(
                  'main, [role="main"], .legal-card, .app-container'
                ) || body;
                const primaryHeader = document.querySelector('[data-primary-page-header]');
                primaryReady = hasRenderedSurface(primaryScope) &&
                  (!primaryHeader || isVisible(primaryHeader));
              }
              const fallbackReady = allowDocumentFallback &&
                document.readyState === 'complete' &&
                !home && hasRenderedSurface(body);
              const stateKey = '__munitterAndroidStartupPresentation';
              if (!primaryReady && !fallbackReady) {
                const previous = window[stateKey];
                if (previous && previous.generation === generation) {
                  previous.frames = 0;
                  previous.qualified = false;
                }
                return 'pending';
              }

              let state = window[stateKey];
              if (!state || state.generation !== generation || !state.qualified) {
                state = { generation, frames: 0, qualified: true };
                window[stateKey] = state;
                requestAnimationFrame(() => {
                  if (window[stateKey] !== state || !state.qualified) return;
                  state.frames = 1;
                  requestAnimationFrame(() => {
                    if (window[stateKey] === state && state.qualified) state.frames = 2;
                  });
                });
                return 'pending';
              }
              return state.frames >= 2 ? 'ready' : 'pending';
            })()
        """.trimIndent()
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
