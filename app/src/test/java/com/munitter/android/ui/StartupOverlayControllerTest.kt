package com.munitter.android.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupOverlayControllerTest {
    @Test
    fun `cold launch remains visible until the active WebView is ready`() {
        val controller = StartupOverlayController(enabled = true)

        assertTrue(controller.isVisible)
        controller.onNavigationStarted(generation = 1)
        assertTrue(controller.isVisible)
        assertTrue(controller.onPresentationReady(generation = 1))
        assertFalse(controller.isVisible)
    }

    @Test
    fun `formal navigation failure releases the startup overlay`() {
        val controller = StartupOverlayController(enabled = true)

        controller.onNavigationStarted(generation = 4)

        assertTrue(controller.onNavigationFailed(generation = 4))
        assertFalse(controller.isVisible)
    }

    @Test
    fun `pre-navigation WebView callback cannot release the startup overlay`() {
        val controller = StartupOverlayController(enabled = true)

        assertFalse(controller.onNavigationFailed(generation = 0))
        assertFalse(controller.onNavigationFailed(generation = null))
        assertTrue(controller.isVisible)
    }

    @Test
    fun `foreground navigation never rearms a completed overlay`() {
        val controller = StartupOverlayController(enabled = true)
        controller.onNavigationStarted(generation = 1)
        controller.onPresentationReady(generation = 1)

        controller.onNavigationStarted(generation = 2)

        assertFalse(controller.isVisible)
        assertFalse(controller.onPresentationReady(generation = 2))
    }

    @Test
    fun `stale visual callback cannot release a newer startup navigation`() {
        val controller = StartupOverlayController(enabled = true)
        controller.onNavigationStarted(generation = 10)
        controller.onNavigationStarted(generation = 11)

        assertFalse(controller.onPresentationReady(generation = 10))
        assertTrue(controller.isVisible)
        assertTrue(controller.onPresentationReady(generation = 11))
        assertFalse(controller.isVisible)
    }

    @Test
    fun `redirect failure from the previous generation cannot release overlay`() {
        val controller = StartupOverlayController(enabled = true)
        controller.onNavigationStarted(generation = 20)
        controller.onNavigationStarted(generation = 21)

        assertFalse(controller.onNavigationFailed(generation = 20))
        assertTrue(controller.isVisible)
        assertTrue(controller.onNavigationFailed(generation = 21))
        assertFalse(controller.isVisible)
    }

    @Test
    fun `disabled flavor never shows startup overlay`() {
        val controller = StartupOverlayController(enabled = false)

        controller.onNavigationStarted(generation = 1)

        assertFalse(controller.isVisible)
        assertFalse(controller.onPresentationReady(generation = 1))
        assertFalse(controller.onWebViewUnavailable())
    }
}
