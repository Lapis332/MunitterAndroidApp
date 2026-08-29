package com.munitter.android.navigation

import java.net.URI
import java.util.Locale

/**
 * Restricts the Android predictive-back bridge to Development's retained
 * Settings routes.  The page still makes the final ownership decision: a
 * direct document load returns false and follows the ordinary WebView path.
 */
object SettingsInteractiveBackPolicy {
    private val retainedPaths = setOf(
        "/settings",
        "/settings/theme",
        "/settings/privacy",
        "/settings/security",
    )

    const val BEGIN_SCRIPT =
        "Boolean(window.MunitterSettingsSpecialTransition?.beginNativeBack?.())"
    const val COMPLETE_SCRIPT =
        "Boolean(window.MunitterSettingsSpecialTransition?.completeNativeBack?.())"
    const val CANCEL_SCRIPT =
        "Boolean(window.MunitterSettingsSpecialTransition?.cancelNativeBack?.())"

    fun canAttempt(
        environment: String,
        internalHost: String,
        currentUrl: String?,
        edge: HorizontalHistoryGestureEdge,
    ): Boolean {
        if (!environment.equals("development", ignoreCase = true) ||
            edge != HorizontalHistoryGestureEdge.LEFT || currentUrl.isNullOrBlank()
        ) {
            return false
        }
        val uri = runCatching { URI(currentUrl) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals(internalHost, ignoreCase = true) &&
            uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port == 443) &&
            retainedPaths.contains(uri.path)
    }

    fun progressScript(progress: Float): String {
        val finiteProgress = if (progress.isFinite()) progress else 0f
        val normalized = finiteProgress.coerceIn(0f, 1f)
        val literal = String.format(Locale.US, "%.6f", normalized)
        return "Boolean(window.MunitterSettingsSpecialTransition?.updateNativeBack?.($literal))"
    }
}
