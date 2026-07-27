package com.munitter.android.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationPolicyTest {
    private val policy = NavigationPolicy(INTERNAL_HOST)

    @Test
    fun `blank and malformed URLs are blocked without a parsed URI`() {
        listOf(null, "", "   ", "https://[broken").forEach { rawUrl ->
            val decision = policy.classify(rawUrl, oauthInProgress = false)

            assertEquals(NavigationTarget.BLOCKED, decision.target)
            assertNull(decision.uri)
        }
    }

    @Test
    fun `exact HTTPS internal host stays in the WebView`() {
        listOf(
            "https://dev.munitter.com/",
            "HTTPS://DEV.MUNITTER.COM/home",
            "https://dev.munitter.com:443/posts/42?from=app#replies",
        ).forEach { rawUrl ->
            assertEquals(
                "Expected internal navigation for $rawUrl",
                NavigationTarget.INTERNAL,
                policy.classify(rawUrl, oauthInProgress = false).target,
            )
        }
    }

    @Test
    fun `HTTPS links outside the exact internal host open externally`() {
        listOf(
            "https://example.com/",
            "https://sub.dev.munitter.com/",
            "https://dev.munitter.com.example.com/",
            "https://munitter.com/",
            "https://x.com/home",
        ).forEach { rawUrl ->
            assertEquals(
                "Expected external navigation for $rawUrl",
                NavigationTarget.EXTERNAL_BROWSER,
                policy.classify(rawUrl, oauthInProgress = false).target,
            )
        }
    }

    @Test
    fun `OAuth authorize endpoints stay in the WebView without prior OAuth state`() {
        listOf(
            "https://x.com/i/oauth2/authorize",
            "https://www.x.com/i/oauth2/authorize/",
            "https://twitter.com/I/OAUTH2/AUTHORIZE?client_id=test",
            "https://www.twitter.com/i/oauth2/authorize",
        ).forEach { rawUrl ->
            assertEquals(
                "Expected OAuth navigation for $rawUrl",
                NavigationTarget.OAUTH_IN_WEBVIEW,
                policy.classify(rawUrl, oauthInProgress = false).target,
            )
        }
    }

    @Test
    fun `OAuth state keeps later X and Twitter pages in the WebView`() {
        listOf(
            "https://x.com/login",
            "https://www.x.com/account/access",
            "https://twitter.com/oauth/authenticate",
            "https://www.twitter.com/home",
        ).forEach { rawUrl ->
            assertEquals(
                "Expected OAuth continuation for $rawUrl",
                NavigationTarget.OAUTH_IN_WEBVIEW,
                policy.classify(rawUrl, oauthInProgress = true).target,
            )
        }
    }

    @Test
    fun `OAuth allowlist does not include lookalike hosts or insecure transport`() {
        val decisions = mapOf(
            "https://api.x.com/i/oauth2/authorize" to NavigationTarget.EXTERNAL_BROWSER,
            "https://x.com.example.org/i/oauth2/authorize" to NavigationTarget.EXTERNAL_BROWSER,
            "https://example.org/i/oauth2/authorize" to NavigationTarget.EXTERNAL_BROWSER,
            "http://x.com/i/oauth2/authorize" to NavigationTarget.BLOCKED,
        )

        decisions.forEach { (rawUrl, expectedTarget) ->
            assertEquals(
                "Unexpected OAuth routing for $rawUrl",
                expectedTarget,
                policy.classify(rawUrl, oauthInProgress = true).target,
            )
        }
    }

    @Test
    fun `supported platform schemes are delegated as special intents`() {
        listOf(
            "mailto:support@munitter.com",
            "TEL:+81123456789",
            "intent://scan/#Intent;scheme=zxing;package=com.example;end",
        ).forEach { rawUrl ->
            assertEquals(
                "Expected a special intent for $rawUrl",
                NavigationTarget.SPECIAL_INTENT,
                policy.classify(rawUrl, oauthInProgress = false).target,
            )
        }
    }

    @Test
    fun `dangerous and unsupported schemes are blocked`() {
        listOf(
            "http://dev.munitter.com/",
            "https://dev.munitter.com:8443/",
            "https://example.com:444/",
            "javascript:alert(1)",
            "data:text/html,<h1>unsafe</h1>",
            "file:///data/local/tmp/page.html",
            "content://com.example.provider/private",
            "ftp://dev.munitter.com/archive.zip",
            "//dev.munitter.com/path",
        ).forEach { rawUrl ->
            assertEquals(
                "Expected blocked navigation for $rawUrl",
                NavigationTarget.BLOCKED,
                policy.classify(rawUrl, oauthInProgress = false).target,
            )
        }
    }

    @Test
    fun `URLs containing user information are blocked before host routing`() {
        listOf(
            "https://user@dev.munitter.com/",
            "https://user:password@dev.munitter.com/",
            "https://dev.munitter.com@evil.example/",
            "https://user@x.com/i/oauth2/authorize",
        ).forEach { rawUrl ->
            assertEquals(
                "Expected credentials-bearing URL to be blocked: $rawUrl",
                NavigationTarget.BLOCKED,
                policy.classify(rawUrl, oauthInProgress = true).target,
            )
        }
    }

    @Test
    fun `trusted origin accepts only HTTPS default or 443 port on exact host`() {
        listOf(
            "https://dev.munitter.com",
            "https://dev.munitter.com/",
            "HTTPS://DEV.MUNITTER.COM:443/path?query=value",
        ).forEach { rawUrl ->
            assertTrue("Expected trusted origin for $rawUrl", policy.isTrustedOrigin(rawUrl))
        }

        listOf(
            null,
            "",
            "http://dev.munitter.com/",
            "https://dev.munitter.com:80/",
            "https://dev.munitter.com:8443/",
            "https://sub.dev.munitter.com/",
            "https://dev.munitter.com.example.org/",
            "https://[broken",
        ).forEach { rawUrl ->
            assertFalse("Expected untrusted origin for $rawUrl", policy.isTrustedOrigin(rawUrl))
        }
    }

    private companion object {
        const val INTERNAL_HOST = "dev.munitter.com"
    }
}
