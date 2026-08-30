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
                assertEquals("munitter-dev-fcm-2026-db973d", BuildConfig.FIREBASE_PROJECT_ID)
                assertEquals("", BuildConfig.CLOUDFLARE_ACCESS_HOST)
                assertEquals("", BuildConfig.CLOUDFLARE_ACCESS_CALLBACK_HOST)
                assertEquals("DEV", BuildConfig.ENVIRONMENT_BADGE)
                assertTrue(BuildConfig.WEBVIEW_DEBUGGABLE)
                assertTrue(
                    BuildConfig.APPLICATION_ID == "com.munitter.android.development" ||
                        BuildConfig.APPLICATION_ID == "com.munitter.android.development.debug",
                )
            }
            "production" -> {
                assertEquals("https://munitter.com/", BuildConfig.BASE_URL)
                assertEquals("munitter.com", BuildConfig.INTERNAL_HOST)
                assertEquals("munitter-prod-fcm-2026-df60ow", BuildConfig.FIREBASE_PROJECT_ID)
                assertEquals("munitter.cloudflareaccess.com", BuildConfig.CLOUDFLARE_ACCESS_HOST)
                assertEquals("www.munitter.com", BuildConfig.CLOUDFLARE_ACCESS_CALLBACK_HOST)
                assertEquals("", BuildConfig.ENVIRONMENT_BADGE)
                assertFalse(BuildConfig.WEBVIEW_DEBUGGABLE)
                assertFalse(BuildConfig.APPLICATION_ID.contains(".development"))
                assertEquals("com.munitter.android", BuildConfig.APPLICATION_ID)
            }
            else -> error("Unexpected environment: ${BuildConfig.ENVIRONMENT}")
        }
    }

    @Test
    fun `third party cookies remain disabled until device evidence requires them`() {
        assertFalse(BuildConfig.ACCEPT_THIRD_PARTY_COOKIES)
    }
}
