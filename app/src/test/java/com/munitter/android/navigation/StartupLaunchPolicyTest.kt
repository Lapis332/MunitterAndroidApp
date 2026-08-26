package com.munitter.android.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupLaunchPolicyTest {
    @Test
    fun `development starts at home without the root redirect`() {
        assertEquals(
            "https://dev.munitter.com/home",
            StartupLaunchPolicy.defaultUrl(
                baseUrl = "https://dev.munitter.com/",
                environment = "development",
            ),
        )
    }

    @Test
    fun `production startup URL remains unchanged`() {
        assertEquals(
            "https://munitter.com/",
            StartupLaunchPolicy.defaultUrl(
                baseUrl = "https://munitter.com/",
                environment = "production",
            ),
        )
    }

    @Test
    fun `development URL normalization never creates a double slash`() {
        assertEquals(
            "https://dev.munitter.com/home",
            StartupLaunchPolicy.defaultUrl(
                baseUrl = "https://dev.munitter.com////",
                environment = "Development",
            ),
        )
    }

    @Test
    fun `known Development session fast path requires both policy and prior authentication`() {
        assertTrue(StartupLaunchPolicy.shouldUseKnownDevelopmentSession(true, true))
        assertFalse(StartupLaunchPolicy.shouldUseKnownDevelopmentSession(true, false))
        assertFalse(StartupLaunchPolicy.shouldUseKnownDevelopmentSession(false, true))
    }

    @Test
    fun `only the internal root is the Development authentication entry point`() {
        assertTrue(
            StartupLaunchPolicy.isDevelopmentAuthenticationEntryPoint(
                "https://dev.munitter.com/",
                "dev.munitter.com",
            ),
        )
        assertFalse(
            StartupLaunchPolicy.isDevelopmentAuthenticationEntryPoint(
                "https://dev.munitter.com/home",
                "dev.munitter.com",
            ),
        )
        assertFalse(
            StartupLaunchPolicy.isDevelopmentAuthenticationEntryPoint(
                "https://example.com/",
                "dev.munitter.com",
            ),
        )
    }
}
