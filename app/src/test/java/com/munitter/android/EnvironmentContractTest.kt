package com.munitter.android

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentContractTest {
    @Test
    fun `build flavor fixes the expected HTTPS origin and debug policy`() {
        val baseUri = URI(BuildConfig.BASE_URL)
        assertEquals("https", baseUri.scheme)
        assertEquals(BuildConfig.INTERNAL_HOST, baseUri.host)
        assertEquals("/", baseUri.path)
        assertEquals(-1, baseUri.port)

        when (BuildConfig.ENVIRONMENT) {
            "development" -> {
                assertEquals("https://dev.munitter.com/", BuildConfig.BASE_URL)
                assertEquals("dev.munitter.com", BuildConfig.INTERNAL_HOST)
                assertTrue(BuildConfig.WEBVIEW_DEBUGGABLE)
                assertTrue(BuildConfig.APPLICATION_ID.contains(".development"))
            }
            "production" -> {
                assertEquals("https://munitter.com/", BuildConfig.BASE_URL)
                assertEquals("munitter.com", BuildConfig.INTERNAL_HOST)
                assertFalse(BuildConfig.WEBVIEW_DEBUGGABLE)
                assertFalse(BuildConfig.APPLICATION_ID.contains(".development"))
            }
            else -> error("Unexpected environment: ${BuildConfig.ENVIRONMENT}")
        }
    }

    @Test
    fun `third party cookies remain disabled until device evidence requires them`() {
        assertFalse(BuildConfig.ACCEPT_THIRD_PARTY_COOKIES)
    }
}
