package com.munitter.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevelopmentEdgeToEdgeTest {
    @Test
    fun edgeToEdgeIsLimitedToDevelopment() {
        assertTrue(DevelopmentEdgeToEdge.isEnabled("development"))
        assertTrue(DevelopmentEdgeToEdge.isEnabled("Development"))
        assertFalse(DevelopmentEdgeToEdge.isEnabled("production"))
    }

    @Test
    fun headerSnapshotAddsTheMeasuredTopInset() {
        assertEquals(
            255,
            DevelopmentEdgeToEdge.headerSnapshotHeightPx(
                baseHeightPx = 158,
                topInsetPx = 97,
                webViewHeightPx = 2_340,
            ),
        )
    }

    @Test
    fun headerSnapshotIsClampedToTheWebView() {
        assertEquals(
            120,
            DevelopmentEdgeToEdge.headerSnapshotHeightPx(
                baseHeightPx = 158,
                topInsetPx = 97,
                webViewHeightPx = 120,
            ),
        )
    }
}
