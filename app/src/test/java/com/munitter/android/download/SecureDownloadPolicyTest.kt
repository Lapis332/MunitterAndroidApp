package com.munitter.android.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureDownloadPolicyTest {
    private val policy = SecureDownloadPolicy("dev.munitter.com")

    @Test
    fun `approved HTTPS download hosts are accepted`() {
        listOf(
            "https://dev.munitter.com/article/media/42",
            "https://dev.munitter.com:443/dm/media/42?download=1",
            "https://media.munitter.com/public/file.jpg",
            "https://account-id.r2.cloudflarestorage.com/bucket/signed-object?signature=masked",
        ).forEach { url ->
            assertTrue("Expected allowed download: $url", policy.isAllowed(url))
        }
    }

    @Test
    fun `untrusted unsafe or credentials-bearing downloads are rejected`() {
        listOf(
            null,
            "",
            "http://dev.munitter.com/article/media/42",
            "https://dev.munitter.com:8443/article/media/42",
            "https://user@dev.munitter.com/article/media/42",
            "https://example.com/file.zip",
            "https://r2.cloudflarestorage.com/file.zip",
            "https://r2.cloudflarestorage.com.example.com/file.zip",
            "file:///sdcard/private",
            "blob:https://dev.munitter.com/id",
        ).forEach { url ->
            assertFalse("Expected blocked download: $url", policy.isAllowed(url))
        }
    }

    @Test
    fun `cookies are eligible only for the exact environment host`() {
        assertTrue(policy.isInternal("https://dev.munitter.com/protected"))
        assertTrue(policy.isInternal("https://dev.munitter.com:443/protected"))
        assertFalse(policy.isInternal("https://media.munitter.com/file"))
        assertFalse(policy.isInternal("https://sub.dev.munitter.com/file"))
        assertFalse(policy.isInternal("http://dev.munitter.com/file"))
    }
}
