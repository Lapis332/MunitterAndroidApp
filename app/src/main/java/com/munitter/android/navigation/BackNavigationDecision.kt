package com.munitter.android.navigation

enum class BackNavigationDecision {
    CLOSE_FULLSCREEN,
    WEBVIEW_BACK,
    FINISH_ACTIVITY,
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
