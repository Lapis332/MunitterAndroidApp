package com.munitter.android.navigation

import java.net.URI
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OAuthNavigationStateTest {
    @Test
    fun `state starts with the requested initial value`() {
        assertFalse(OAuthNavigationState().isInProgress)
        assertTrue(OAuthNavigationState(initialValue = true).isInProgress)
    }

    @Test
    fun `OAuth target starts OAuth even when the URI is unavailable`() {
        val state = OAuthNavigationState()

        state.recordBeforeNavigation(
            uri = null,
            target = NavigationTarget.OAUTH_IN_WEBVIEW,
        )

        assertTrue(state.isInProgress)
    }

    @Test
    fun `internal X start and callback paths start OAuth case insensitively`() {
        listOf(
            "https://dev.munitter.com/Auth/XStart",
            "https://dev.munitter.com/auth/xstart",
            "https://dev.munitter.com/Auth/XCallback",
            "https://dev.munitter.com/AUTH/XCALLBACK?code=test",
        ).forEach { rawUrl ->
            val state = OAuthNavigationState()

            state.recordBeforeNavigation(
                uri = URI(rawUrl),
                target = NavigationTarget.INTERNAL,
            )

            assertTrue("Expected OAuth to start for $rawUrl", state.isInProgress)
        }
    }

    @Test
    fun `ordinary navigation does not start OAuth`() {
        val state = OAuthNavigationState()

        state.recordBeforeNavigation(
            uri = URI("https://dev.munitter.com/home"),
            target = NavigationTarget.INTERNAL,
        )

        assertFalse(state.isInProgress)
    }

    @Test
    fun `OAuth-looking path on an external host does not start OAuth`() {
        val state = OAuthNavigationState()

        state.recordBeforeNavigation(
            uri = URI("https://evil.example/Auth/XStart"),
            target = NavigationTarget.EXTERNAL_BROWSER,
        )

        assertFalse(state.isInProgress)
    }

    @Test
    fun `external and missing page finishes do not end OAuth`() {
        val state = OAuthNavigationState(initialValue = true)

        state.recordPageFinished(
            uri = URI("https://x.com/login"),
            internalHost = INTERNAL_HOST,
        )
        assertTrue(state.isInProgress)

        state.recordPageFinished(uri = null, internalHost = INTERNAL_HOST)
        assertTrue(state.isInProgress)
    }

    @Test
    fun `internal OAuth handshake pages keep OAuth in progress`() {
        listOf(
            "https://dev.munitter.com/Auth/XStart",
            "https://DEV.MUNITTER.COM/auth/xcallback?code=test",
        ).forEach { rawUrl ->
            val state = OAuthNavigationState(initialValue = true)

            state.recordPageFinished(
                uri = URI(rawUrl),
                internalHost = INTERNAL_HOST,
            )

            assertTrue("Expected OAuth to continue on $rawUrl", state.isInProgress)
        }
    }

    @Test
    fun `first ordinary internal page after callback ends OAuth`() {
        val state = OAuthNavigationState(initialValue = true)

        state.recordPageFinished(
            uri = URI("https://DEV.MUNITTER.COM/home"),
            internalHost = INTERNAL_HOST,
        )

        assertFalse(state.isInProgress)
    }

    @Test
    fun `page finish cannot create OAuth state`() {
        val state = OAuthNavigationState()

        state.recordPageFinished(
            uri = URI("https://dev.munitter.com/Auth/XCallback"),
            internalHost = INTERNAL_HOST,
        )

        assertFalse(state.isInProgress)
    }

    private companion object {
        const val INTERNAL_HOST = "dev.munitter.com"
    }
}
