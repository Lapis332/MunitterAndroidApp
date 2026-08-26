package com.munitter.android.navigation

import java.net.URI

object StartupLaunchPolicy {
    fun defaultUrl(baseUrl: String, environment: String): String {
        if (!environment.equals("development", ignoreCase = true)) {
            return baseUrl
        }

        return "${baseUrl.trimEnd('/')}/home"
    }

    fun shouldUseKnownDevelopmentSession(
        allowSessionFastPath: Boolean,
        knownAuthenticatedSession: Boolean,
    ): Boolean = allowSessionFastPath && knownAuthenticatedSession

    fun isDevelopmentAuthenticationEntryPoint(rawUrl: String?, internalHost: String): Boolean {
        val uri = runCatching { URI(rawUrl.orEmpty()) }.getOrNull() ?: return false
        if (!uri.host.equals(internalHost, ignoreCase = true)) return false
        return uri.path.isNullOrEmpty() || uri.path == "/"
    }
}
