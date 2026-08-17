package com.munitter.android.web

internal object HeaderPresentationReleasePolicy {
    fun shouldRelease(
        previousOwner: String,
        currentOwner: String,
        state: String,
        timedOut: Boolean,
    ): Boolean {
        val ownerChanged = previousOwner.isNotEmpty() &&
            currentOwner.isNotEmpty() &&
            previousOwner != currentOwner
        val sameKnownOwner = previousOwner.isNotEmpty() && previousOwner == currentOwner
        val presentationReady = state == "ready" || state == "no-avatar"
        val fallbackCanReplace = state == "formal-fallback" && !sameKnownOwner
        return ownerChanged || presentationReady || fallbackCanReplace || timedOut
    }
}
