package com.munitter.android.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class HorizontalHistoryNavigationDeciderTest {
    @Test
    fun `left edge owns back and never falls through to forward`() {
        assertEquals(
            HorizontalHistoryNavigationDecision.WEBVIEW_BACK,
            decide(edge = HorizontalHistoryGestureEdge.LEFT, canBack = true, canForward = true),
        )
        assertEquals(
            HorizontalHistoryNavigationDecision.NO_OP,
            decide(edge = HorizontalHistoryGestureEdge.LEFT, canBack = false, canForward = true),
        )
    }

    @Test
    fun `right edge owns forward and never falls through to back or fullscreen close`() {
        assertEquals(
            HorizontalHistoryNavigationDecision.WEBVIEW_FORWARD,
            decide(edge = HorizontalHistoryGestureEdge.RIGHT, canBack = true, canForward = true),
        )
        assertEquals(
            HorizontalHistoryNavigationDecision.NO_OP,
            decide(
                edge = HorizontalHistoryGestureEdge.RIGHT,
                canBack = true,
                canForward = false,
                isFullscreen = true,
            ),
        )
    }

    @Test
    fun `left edge closes native fullscreen as its current back destination`() {
        assertEquals(
            HorizontalHistoryNavigationDecision.CLOSE_FULLSCREEN,
            decide(
                edge = HorizontalHistoryGestureEdge.LEFT,
                canBack = true,
                canForward = true,
                isFullscreen = true,
            ),
        )
    }

    private fun decide(
        edge: HorizontalHistoryGestureEdge,
        canBack: Boolean,
        canForward: Boolean,
        isFullscreen: Boolean = false,
    ): HorizontalHistoryNavigationDecision = HorizontalHistoryNavigationDecider.decide(
        edge = edge,
        isFullscreenMedia = isFullscreen,
        canWebViewGoBack = canBack,
        canWebViewGoForward = canForward,
    )
}
