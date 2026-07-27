package com.munitter.android.web

import android.webkit.WebViewClient
import androidx.annotation.StringRes
import com.munitter.android.R

enum class WebFailureKind(
    @StringRes val titleRes: Int,
    @StringRes val messageRes: Int,
) {
    OFFLINE(R.string.error_offline_title, R.string.error_offline_message),
    DNS(R.string.error_dns_title, R.string.error_dns_message),
    TIMEOUT(R.string.error_timeout_title, R.string.error_timeout_message),
    TLS(R.string.error_tls_title, R.string.error_tls_message),
    SECURITY(R.string.error_security_title, R.string.error_security_message),
    SERVER(R.string.error_server_title, R.string.error_server_message),
    WEBVIEW_UNAVAILABLE(R.string.error_webview_title, R.string.error_webview_message),
    GENERIC(R.string.error_generic_title, R.string.error_generic_message),
}

object WebFailureClassifier {
    fun fromWebViewError(errorCode: Int, isOnline: Boolean): WebFailureKind {
        if (!isOnline) return WebFailureKind.OFFLINE

        return when (errorCode) {
            WebViewClient.ERROR_HOST_LOOKUP -> WebFailureKind.DNS
            WebViewClient.ERROR_TIMEOUT -> WebFailureKind.TIMEOUT
            WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> WebFailureKind.TLS
            else -> WebFailureKind.GENERIC
        }
    }
}

data class WebUiState(
    val isLoading: Boolean = true,
    val progress: Int = 0,
    val hasVisibleContent: Boolean = false,
    val failure: WebFailureKind? = null,
)
