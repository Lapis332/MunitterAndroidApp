package com.munitter.android.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsInteractiveBackPolicyTest {
    @Test
    fun `Development left edge can probe only retained Settings routes`() {
        listOf(
            "/settings",
            "/settings/theme",
            "/settings/privacy",
            "/settings/security",
        ).forEach { path ->
            assertTrue(
                SettingsInteractiveBackPolicy.canAttempt(
                    environment = "development",
                    internalHost = "dev.munitter.com",
                    currentUrl = "https://dev.munitter.com$path?audit=1#section",
                    edge = HorizontalHistoryGestureEdge.LEFT,
                ),
            )
        }
    }

    @Test
    fun `normal routes right edge external origins and Production never probe Web ownership`() {
        val base = SettingsInteractiveBackPolicy.canAttempt(
            environment = "development",
            internalHost = "dev.munitter.com",
            currentUrl = "https://dev.munitter.com/settings",
            edge = HorizontalHistoryGestureEdge.LEFT,
        )
        assertTrue(base)
        assertFalse(canAttempt(url = "https://dev.munitter.com/home"))
        assertFalse(canAttempt(url = "https://dev.munitter.com/settings/other"))
        assertFalse(canAttempt(url = "https://evil.example/settings"))
        assertFalse(canAttempt(url = "http://dev.munitter.com/settings"))
        assertFalse(canAttempt(url = "https://dev.munitter.com:444/settings"))
        assertFalse(canAttempt(edge = HorizontalHistoryGestureEdge.RIGHT))
        assertFalse(canAttempt(environment = "production"))
    }

    @Test
    fun `progress script is finite locale-independent and clamped`() {
        assertEquals(
            "Boolean(window.MunitterSettingsSpecialTransition?.updateNativeBack?.(0.375000))",
            SettingsInteractiveBackPolicy.progressScript(0.375f),
        )
        assertTrue(SettingsInteractiveBackPolicy.progressScript(-4f).contains("(0.000000)"))
        assertTrue(SettingsInteractiveBackPolicy.progressScript(5f).contains("(1.000000)"))
        assertTrue(SettingsInteractiveBackPolicy.progressScript(Float.NaN).contains("(0.000000)"))
    }

    private fun canAttempt(
        environment: String = "development",
        url: String = "https://dev.munitter.com/settings",
        edge: HorizontalHistoryGestureEdge = HorizontalHistoryGestureEdge.LEFT,
    ): Boolean = SettingsInteractiveBackPolicy.canAttempt(
        environment = environment,
        internalHost = "dev.munitter.com",
        currentUrl = url,
        edge = edge,
    )
}
