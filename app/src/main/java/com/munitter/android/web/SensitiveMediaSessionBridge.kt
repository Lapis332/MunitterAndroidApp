package com.munitter.android.web

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Resets per-resource Sensitive Media reveal grants when the Android app
 * process changes, while preserving them across navigation and ordinary
 * background/foreground transitions in the same process.
 *
 * The process identifier is kept only in memory by the native shell. The web
 * marker and reveal grants live in sessionStorage; no reveal state is written
 * to SharedPreferences, cookies, a database, or other persistent storage.
 */
class SensitiveMediaSessionBridge(
    processSessionId: String,
    internalHost: String,
) {
    private val script = SensitiveMediaSessionPolicy.documentStartScript(processSessionId)
    private val normalizedInternalHost = internalHost.trim().lowercase()
    private val allowedOrigin = Uri.Builder()
        .scheme("https")
        .authority(normalizedInternalHost)
        .build()
        .toString()

    fun attach(webView: WebView) {
        if (allowedOrigin.isBlank() || !WebViewFeature.isFeatureSupported(
                WebViewFeature.DOCUMENT_START_SCRIPT,
            )
        ) {
            return
        }

        WebViewCompat.addDocumentStartJavaScript(
            webView,
            script,
            setOf(allowedOrigin),
        )
    }

    /**
     * Rotates the server capability binding by deleting only its session
     * cookie before the first document of a cold app process is loaded.
     * Authentication and all other WebView cookies remain untouched.
     */
    fun resetServerSessionBindingForColdLaunch(
        cookieManager: CookieManager,
        timeoutMilliseconds: Long = COOKIE_RESET_TIMEOUT_MILLISECONDS,
        onCompleted: (Boolean) -> Unit,
    ) {
        val handler = Handler(Looper.getMainLooper())
        val completed = AtomicBoolean(false)
        lateinit var timeout: Runnable
        val finish: (Boolean) -> Unit = { succeeded ->
            if (completed.compareAndSet(false, true)) {
                handler.removeCallbacks(timeout)
                onCompleted(succeeded)
            }
        }
        timeout = Runnable { finish(false) }
        handler.postDelayed(timeout, timeoutMilliseconds.coerceAtLeast(1L))

        try {
            cookieManager.setCookie(
                allowedOrigin,
                "${SensitiveMediaSessionPolicy.SERVER_SESSION_BINDING_COOKIE}=; " +
                    "Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Lax",
            ) {
                val removed = runCatching {
                    cookieManager.flush()
                    !SensitiveMediaCookiePolicy.containsCookie(
                        cookieManager.getCookie(allowedOrigin),
                        SensitiveMediaSessionPolicy.SERVER_SESSION_BINDING_COOKIE,
                    )
                }.getOrDefault(false)
                finish(removed)
            }
        } catch (_: RuntimeException) {
            finish(false)
        }
    }

    /**
     * Covers restored WebView documents and WebView implementations without a
     * document-start script. The script is idempotent for the current process.
     */
    fun applyToCurrentDocument(webView: WebView) {
        val current = runCatching { Uri.parse(webView.url.orEmpty()) }.getOrNull()
        if (current?.scheme != "https" || current.host.isNullOrBlank()) return
        if (!current.host.equals(normalizedInternalHost, ignoreCase = true)) return
        webView.evaluateJavascript(script, null)
    }

    private companion object {
        const val COOKIE_RESET_TIMEOUT_MILLISECONDS = 2_000L
    }
}

internal object SensitiveMediaCookiePolicy {
    fun containsCookie(rawCookieHeader: String?, cookieName: String): Boolean {
        if (rawCookieHeader.isNullOrBlank() || cookieName.isBlank()) return false
        return rawCookieHeader.split(';').any { pair ->
            pair.trim().substringBefore('=', missingDelimiterValue = "").trim() == cookieName
        }
    }
}

internal data class SensitiveMediaColdLaunchPlan(
    val restoreSavedWebViewState: Boolean,
    val requestedUrl: String?,
)

internal object SensitiveMediaColdLaunchPolicy {
    fun plan(
        isFirstProcessLaunch: Boolean,
        cookieResetSucceeded: Boolean,
        requestedUrl: String?,
        safeFallbackUrl: String,
    ): SensitiveMediaColdLaunchPlan {
        if (!isFirstProcessLaunch || cookieResetSucceeded) {
            return SensitiveMediaColdLaunchPlan(
                restoreSavedWebViewState = true,
                requestedUrl = requestedUrl,
            )
        }

        return SensitiveMediaColdLaunchPlan(
            restoreSavedWebViewState = false,
            requestedUrl = stripQueryAndFragment(safeFallbackUrl),
        )
    }

    private fun stripQueryAndFragment(rawUrl: String): String? {
        val parsed = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return null
        if (!parsed.scheme.equals("https", ignoreCase = true) || parsed.host.isNullOrBlank()) {
            return null
        }
        return runCatching {
            URI(parsed.scheme, parsed.authority, parsed.path.ifBlank { "/" }, null, null).toString()
        }.getOrNull()
    }
}

internal class SensitiveMediaColdLaunchResetCoordinator {
    private val lock = Any()
    private var started = false
    private var completed = false
    private var result = false
    private val callbacks = mutableListOf<(Boolean) -> Unit>()

    fun prepare(
        startReset: ((Boolean) -> Unit) -> Unit,
        onCompleted: (Boolean) -> Unit,
    ) {
        var shouldStart = false
        var immediateResult: Boolean? = null
        synchronized(lock) {
            if (completed) {
                immediateResult = result
            } else {
                callbacks += onCompleted
                if (!started) {
                    started = true
                    shouldStart = true
                }
            }
        }

        immediateResult?.let(onCompleted)
        if (!shouldStart) return
        try {
            startReset(::complete)
        } catch (_: RuntimeException) {
            complete(false)
        }
    }

    private fun complete(succeeded: Boolean) {
        val pending: List<(Boolean) -> Unit>
        synchronized(lock) {
            if (completed) return
            completed = true
            result = succeeded
            pending = callbacks.toList()
            callbacks.clear()
        }
        pending.forEach { callback -> callback(succeeded) }
    }
}

object SensitiveMediaSessionPolicy {
    const val REVEAL_STORAGE_KEY = "munitter.sensitiveMedia.reveals.v1"
    const val NATIVE_SESSION_STORAGE_KEY = "munitter.sensitiveMedia.nativeSession.v1"
    const val SERVER_SESSION_BINDING_COOKIE = "munitter.sensitive-media.session"
    const val CLIENT_SESSION_WINDOW_NAME_PREFIX = "munitter-sensitive-media-session:"

    fun documentStartScript(processSessionId: String): String {
        require(PROCESS_SESSION_ID.matches(processSessionId)) {
            "processSessionId must be a lowercase hexadecimal identifier"
        }

        return """
            (() => {
              const markerKey = '$NATIVE_SESSION_STORAGE_KEY';
              const revealKey = '$REVEAL_STORAGE_KEY';
              const processSessionId = '$processSessionId';
              let isCurrentProcess = false;
              try {
                isCurrentProcess = window.sessionStorage.getItem(markerKey) === processSessionId;
              } catch (_) {
                isCurrentProcess = false;
              }
              if (isCurrentProcess) return;

              try {
                window.sessionStorage.removeItem(revealKey);
              } catch (_) {
                // Continue with the independent window.name and web-hook resets.
              }
              try {
                if (String(window.name || '').startsWith('$CLIENT_SESSION_WINDOW_NAME_PREFIX')) {
                  window.name = '';
                }
              } catch (_) {
                // Never inspect or mutate an unrelated window.name value.
              }
              try {
                window.sessionStorage.setItem(markerKey, processSessionId);
              } catch (_) {
                // A restricted WebView stays concealed and retries the web hook below.
              }

              let remainingAttempts = 200;
              const invokeWebReset = () => {
                try {
                  const hook = window.MunitterApp?.resetSensitiveMediaRevealSession;
                  if (typeof hook === 'function') {
                    hook.call(window.MunitterApp);
                    return;
                  }
                } catch (_) {
                  // Keep retrying for a bounded interval while the web bundle loads.
                }
                if (remainingAttempts <= 0) return;
                remainingAttempts -= 1;
                window.setTimeout(invokeWebReset, 50);
              };
              invokeWebReset();
            })();
        """.trimIndent()
    }

    private val PROCESS_SESSION_ID = Regex("^[0-9a-f]{32}$")
}
