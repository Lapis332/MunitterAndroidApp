package com.munitter.android.notification

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** Exact-origin, main-frame hint only. Web-supplied counts are never trusted. */
internal class NotificationUnreadStateBridge(
    private val internalHost: String,
    private val onHint: () -> Unit,
) {
    private var attached: WebView? = null
    private var script: ScriptHandler? = null

    fun attach(webView: WebView) {
        detach()
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        WebViewCompat.addWebMessageListener(webView, NAME, setOf("https://$internalHost"),
            object : WebViewCompat.WebMessageListener {
                override fun onPostMessage(view: WebView, message: WebMessageCompat,
                    sourceOrigin: Uri, isMainFrame: Boolean, replyProxy: JavaScriptReplyProxy) {
                    if (view !== attached || message.type != WebMessageCompat.TYPE_STRING ||
                        !NotificationUnreadHintPolicy.accepts(sourceOrigin.toString(), view.url,
                            internalHost, isMainFrame, message.data)) return
                    onHint()
                }
            })
        attached = webView
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            script = WebViewCompat.addDocumentStartJavaScript(webView, SCRIPT, setOf("https://$internalHost"))
        }
    }

    fun applyToCurrentDocument(webView: WebView) {
        if (webView === attached && NotificationTapPolicy.isCurrentOrigin(webView.url, internalHost)) {
            webView.evaluateJavascript(SCRIPT, null)
        }
    }

    fun detach() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            script?.remove()
        }
        script = null
        attached?.let { view ->
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                WebViewCompat.removeWebMessageListener(view, NAME)
            }
        }
        attached = null
    }

    private companion object {
        const val NAME = "munitterUnreadStateHint"
        val SCRIPT = """
            (() => {
              if (window.__munitterNativeUnreadHintAttached) return;
              window.__munitterNativeUnreadHintAttached = true;
              window.addEventListener('munitter:unread-state-changed', () => {
                window.munitterUnreadStateHint?.postMessage('unread-state-changed:v1');
              });
            })();
        """.trimIndent()
    }
}

internal object NotificationUnreadHintPolicy {
    fun accepts(sourceOrigin: String?, documentUrl: String?, internalHost: String,
        isMainFrame: Boolean, data: String?): Boolean =
        isMainFrame && data == "unread-state-changed:v1" &&
            NotificationTapPolicy.isCurrentOrigin(sourceOrigin, internalHost) &&
            NotificationTapPolicy.isCurrentOrigin(documentUrl, internalHost)
}
