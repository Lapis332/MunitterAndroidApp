package com.munitter.android.navigation

import java.net.URI

object StartupLaunchPolicy {
    private const val DEVELOPMENT_SESSION_COOKIE_NAME = "__Host-MunitterSessionToken"

    fun defaultUrl(baseUrl: String, environment: String): String {
        if (!environment.equals("development", ignoreCase = true)) {
            return baseUrl
        }

        return "${baseUrl.trimEnd('/')}/home"
    }

    fun hasPreservedDevelopmentSession(cookieHeader: String?): Boolean =
        cookieHeader
            ?.split(';')
            ?.map(String::trim)
            ?.any { cookie ->
                val separator = cookie.indexOf('=')
                separator > 0 &&
                    cookie.substring(0, separator).trim() == DEVELOPMENT_SESSION_COOKIE_NAME &&
                    cookie.substring(separator + 1).isNotBlank()
            } == true

    fun isDevelopmentAuthenticationEntryPoint(rawUrl: String?, internalHost: String): Boolean {
        val uri = runCatching { URI(rawUrl.orEmpty()) }.getOrNull() ?: return false
        if (!uri.host.equals(internalHost, ignoreCase = true)) return false
        return uri.path.isNullOrEmpty() || uri.path == "/"
    }
}
