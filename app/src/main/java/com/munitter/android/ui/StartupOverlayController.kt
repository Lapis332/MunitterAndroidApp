package com.munitter.android.ui

internal class StartupOverlayController(
    enabled: Boolean,
) {
    var isVisible: Boolean = enabled
        private set

    private var activeNavigationGeneration: Long? = null

    fun onNavigationStarted(generation: Long) {
        if (!isVisible) return
        activeNavigationGeneration = generation
    }

    fun onPresentationReady(generation: Long): Boolean {
        if (!isVisible || generation != activeNavigationGeneration) return false
        return complete()
    }

    fun onNavigationFailed(generation: Long?): Boolean {
        if (!isVisible) return false
        if (
            generation == null ||
            generation <= 0L ||
            activeNavigationGeneration == null ||
            generation != activeNavigationGeneration
        ) {
            return false
        }
        return complete()
    }

    fun onWebViewUnavailable(): Boolean = if (isVisible) complete() else false

    private fun complete(): Boolean {
        isVisible = false
        activeNavigationGeneration = null
        return true
    }
}
