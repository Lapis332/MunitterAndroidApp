package com.munitter.android.ui

internal object DevelopmentEdgeToEdge {
    fun isEnabled(environment: String): Boolean =
        environment.equals("development", ignoreCase = true)

    fun headerSnapshotHeightPx(
        baseHeightPx: Int,
        topInsetPx: Int,
        webViewHeightPx: Int,
    ): Int = (baseHeightPx + topInsetPx.coerceAtLeast(0))
        .coerceIn(1, webViewHeightPx.coerceAtLeast(1))
}
