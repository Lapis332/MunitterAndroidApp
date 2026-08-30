package com.munitter.android.navigation

class NavigationCoordinator(
    private val policy: NavigationPolicy,
    private val oauthState: OAuthNavigationState,
    private val externalNavigator: ExternalNavigator,
    private val openInternalUrl: (String) -> Unit,
) {
    fun shouldOverrideMainFrame(
        rawUrl: String?,
        hasUserGesture: Boolean,
    ): Boolean {
        val decision = policy.classify(rawUrl, oauthState.isInProgress)
        oauthState.recordBeforeNavigation(decision.uri, decision.target)

        return when (decision.target) {
            NavigationTarget.INTERNAL,
            NavigationTarget.ACCESS_IN_WEBVIEW,
            NavigationTarget.OAUTH_IN_WEBVIEW,
            -> false
            NavigationTarget.EXTERNAL_BROWSER,
            NavigationTarget.SPECIAL_INTENT,
            -> {
                if (hasUserGesture) {
                    externalNavigator.open(decision)
                }
                true
            }
            NavigationTarget.BLOCKED -> {
                if (hasUserGesture) {
                    externalNavigator.showBlocked()
                }
                true
            }
        }
    }

    fun openPopup(rawUrl: String?) {
        val decision = policy.classify(rawUrl, oauthState.isInProgress)
        oauthState.recordBeforeNavigation(decision.uri, decision.target)
        when (decision.target) {
            NavigationTarget.INTERNAL,
            NavigationTarget.ACCESS_IN_WEBVIEW,
            NavigationTarget.OAUTH_IN_WEBVIEW,
            -> decision.uri?.toString()?.let { openInternalUrl(it) }
            NavigationTarget.EXTERNAL_BROWSER,
            NavigationTarget.SPECIAL_INTENT,
            -> externalNavigator.open(decision)
            NavigationTarget.BLOCKED -> externalNavigator.showBlocked()
        }
    }

    fun allowsMainFrameNetworkRequest(rawUrl: String?): Boolean {
        val decision = policy.classify(rawUrl, oauthState.isInProgress)
        oauthState.recordBeforeNavigation(decision.uri, decision.target)
        return decision.target == NavigationTarget.INTERNAL ||
            decision.target == NavigationTarget.ACCESS_IN_WEBVIEW ||
            decision.target == NavigationTarget.OAUTH_IN_WEBVIEW
    }
}
