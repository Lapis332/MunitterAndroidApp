package com.munitter.android.navigation

import java.net.URI
import java.util.Locale

enum class NavigationTarget {
    INTERNAL,
    ACCESS_IN_WEBVIEW,
    OAUTH_IN_WEBVIEW,
    EXTERNAL_BROWSER,
    SPECIAL_INTENT,
    BLOCKED,
}

data class NavigationDecision(
    val target: NavigationTarget,
    val uri: URI?,
)

class NavigationPolicy(
    internalHost: String,
    cloudflareAccessHost: String = "",
) {
    private val internalHost = internalHost.lowercase(Locale.US)
    private val cloudflareAccessHost = cloudflareAccessHost.lowercase(Locale.US)
    private val oauthHosts = setOf("twitter.com", "www.twitter.com", "x.com", "www.x.com")

    fun classify(rawUrl: String?, oauthInProgress: Boolean): NavigationDecision {
        if (rawUrl.isNullOrBlank()) {
            return NavigationDecision(NavigationTarget.BLOCKED, null)
        }

        val uri = runCatching { URI(rawUrl) }.getOrNull()
            ?: return NavigationDecision(NavigationTarget.BLOCKED, null)
        val scheme = uri.scheme?.lowercase(Locale.US)

        if (scheme == "mailto" || scheme == "tel" || scheme == "intent") {
            return NavigationDecision(NavigationTarget.SPECIAL_INTENT, uri)
        }

        if (scheme != "https" || !uri.userInfo.isNullOrEmpty()) {
            return NavigationDecision(NavigationTarget.BLOCKED, uri)
        }

        val host = uri.host?.lowercase(Locale.US)
            ?: return NavigationDecision(NavigationTarget.BLOCKED, uri)
        if (uri.port !in setOf(-1, 443)) {
            return NavigationDecision(NavigationTarget.BLOCKED, uri)
        }

        if (host == internalHost) {
            return NavigationDecision(NavigationTarget.INTERNAL, uri)
        }

        if (
            cloudflareAccessHost.isNotEmpty() &&
            host == cloudflareAccessHost &&
            isCloudflareAccessPath(uri.path)
        ) {
            return NavigationDecision(NavigationTarget.ACCESS_IN_WEBVIEW, uri)
        }

        if (host in oauthHosts && (oauthInProgress || isOAuthAuthorizePath(uri.path))) {
            return NavigationDecision(NavigationTarget.OAUTH_IN_WEBVIEW, uri)
        }

        return NavigationDecision(NavigationTarget.EXTERNAL_BROWSER, uri)
    }

    fun isTrustedOrigin(rawUrl: String?): Boolean {
        val uri = runCatching { URI(rawUrl.orEmpty()) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals(internalHost, ignoreCase = true) &&
            uri.port in setOf(-1, 443)
    }

    private fun isOAuthAuthorizePath(path: String?): Boolean {
        val normalized = path.orEmpty().trimEnd('/').lowercase(Locale.US)
        return normalized == "/i/oauth2/authorize"
    }

    private fun isCloudflareAccessPath(path: String?): Boolean {
        val normalized = path.orEmpty().trimEnd('/').lowercase(Locale.US)
        return normalized == "/cdn-cgi/access" ||
            normalized.startsWith("/cdn-cgi/access/")
    }
}

class OAuthNavigationState(
    initialValue: Boolean = false,
) {
    @Volatile
    var isInProgress: Boolean = initialValue
        private set

    fun recordBeforeNavigation(uri: URI?, target: NavigationTarget) {
        val path = uri?.path.orEmpty()
        if (
            target == NavigationTarget.OAUTH_IN_WEBVIEW ||
            (
                target == NavigationTarget.INTERNAL &&
                    (
                        path.equals("/Auth/XStart", ignoreCase = true) ||
                            path.equals("/Auth/XCallback", ignoreCase = true)
                        )
                )
        ) {
            isInProgress = true
        }
    }

    fun recordPageFinished(uri: URI?, internalHost: String) {
        if (!isInProgress || !uri?.host.equals(internalHost, ignoreCase = true)) {
            return
        }

        val path = uri?.path.orEmpty()
        if (
            !path.equals("/Auth/XStart", ignoreCase = true) &&
            !path.equals("/Auth/XCallback", ignoreCase = true)
        ) {
            isInProgress = false
        }
    }
}
