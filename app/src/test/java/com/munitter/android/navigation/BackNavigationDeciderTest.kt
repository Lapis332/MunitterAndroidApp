package com.munitter.android.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class BackNavigationDeciderTest {
    @Test
    fun `fullscreen media is closed before WebView history is used`() {
        assertEquals(
            BackNavigationDecision.CLOSE_FULLSCREEN,
            BackNavigationDecider.decide(
                isFullscreenMedia = true,
                canWebViewGoBack = true,
            ),
        )
        assertEquals(
            BackNavigationDecision.CLOSE_FULLSCREEN,
            BackNavigationDecider.decide(
                isFullscreenMedia = true,
                canWebViewGoBack = false,
            ),
        )
    }

    @Test
    fun `WebView navigates back when no fullscreen media is open`() {
        assertEquals(
            BackNavigationDecision.WEBVIEW_BACK,
            BackNavigationDecider.decide(
                isFullscreenMedia = false,
                canWebViewGoBack = true,
            ),
        )
    }

    @Test
    fun `activity finishes when there is nothing else to close or navigate`() {
        assertEquals(
            BackNavigationDecision.FINISH_ACTIVITY,
            BackNavigationDecider.decide(
                isFullscreenMedia = false,
                canWebViewGoBack = false,
            ),
        )
    }
}
