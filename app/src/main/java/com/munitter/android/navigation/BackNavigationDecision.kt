package com.munitter.android.navigation

enum class BackNavigationDecision {
    CLOSE_FULLSCREEN,
    WEBVIEW_BACK,
    FINISH_ACTIVITY,
}

enum class HorizontalHistoryGestureEdge {
    LEFT,
    RIGHT,
}

enum class HorizontalHistoryNavigationDecision {
    CLOSE_FULLSCREEN,
    WEBVIEW_BACK,
    WEBVIEW_FORWARD,
    NO_OP,
}

object BackNavigationDecider {
    fun decide(
        isFullscreenMedia: Boolean,
        canWebViewGoBack: Boolean,
    ): BackNavigationDecision = when {
        isFullscreenMedia -> BackNavigationDecision.CLOSE_FULLSCREEN
        canWebViewGoBack -> BackNavigationDecision.WEBVIEW_BACK
        else -> BackNavigationDecision.FINISH_ACTIVITY
    }
}

/**
 * Development-only system-edge history routing.
 *
 * Android reports both left- and right-edge system gestures through the back
 * dispatcher. The caller enables this policy only for the Development flavor,
 * then maps the immutable start edge to exactly one history direction. A
 * missing history entry deliberately remains a no-op and never falls through
 * to the opposite direction or Activity dismissal.
 */
object HorizontalHistoryNavigationDecider {
    fun decide(
        edge: HorizontalHistoryGestureEdge,
        isFullscreenMedia: Boolean,
        canWebViewGoBack: Boolean,
        canWebViewGoForward: Boolean,
    ): HorizontalHistoryNavigationDecision = when (edge) {
        HorizontalHistoryGestureEdge.LEFT -> when {
            isFullscreenMedia -> HorizontalHistoryNavigationDecision.CLOSE_FULLSCREEN
            canWebViewGoBack -> HorizontalHistoryNavigationDecision.WEBVIEW_BACK
            else -> HorizontalHistoryNavigationDecision.NO_OP
        }
        HorizontalHistoryGestureEdge.RIGHT -> when {
            canWebViewGoForward -> HorizontalHistoryNavigationDecision.WEBVIEW_FORWARD
            else -> HorizontalHistoryNavigationDecision.NO_OP
        }
    }
}
