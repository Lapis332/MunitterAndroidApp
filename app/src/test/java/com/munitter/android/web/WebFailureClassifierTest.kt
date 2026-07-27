package com.munitter.android.web

import android.webkit.WebViewClient
import org.junit.Assert.assertEquals
import org.junit.Test

class WebFailureClassifierTest {
    @Test
    fun `offline state takes precedence over the WebView error code`() {
        listOf(
            WebViewClient.ERROR_HOST_LOOKUP,
            WebViewClient.ERROR_TIMEOUT,
            WebViewClient.ERROR_FAILED_SSL_HANDSHAKE,
            12345,
        ).forEach { errorCode ->
            assertEquals(
                WebFailureKind.OFFLINE,
                WebFailureClassifier.fromWebViewError(
                    errorCode = errorCode,
                    isOnline = false,
                ),
            )
        }
    }

    @Test
    fun `host lookup errors are classified as DNS failures when online`() {
        assertEquals(
            WebFailureKind.DNS,
            WebFailureClassifier.fromWebViewError(
                errorCode = WebViewClient.ERROR_HOST_LOOKUP,
                isOnline = true,
            ),
        )
    }

    @Test
    fun `timeout errors are classified as timeout failures when online`() {
        assertEquals(
            WebFailureKind.TIMEOUT,
            WebFailureClassifier.fromWebViewError(
                errorCode = WebViewClient.ERROR_TIMEOUT,
                isOnline = true,
            ),
        )
    }

    @Test
    fun `SSL handshake errors are classified as TLS failures when online`() {
        assertEquals(
            WebFailureKind.TLS,
            WebFailureClassifier.fromWebViewError(
                errorCode = WebViewClient.ERROR_FAILED_SSL_HANDSHAKE,
                isOnline = true,
            ),
        )
    }

    @Test
    fun `unrecognized online errors are classified as generic failures`() {
        listOf(Int.MIN_VALUE, 0, 12345, Int.MAX_VALUE).forEach { errorCode ->
            assertEquals(
                WebFailureKind.GENERIC,
                WebFailureClassifier.fromWebViewError(
                    errorCode = errorCode,
                    isOnline = true,
                ),
            )
        }
    }
}
