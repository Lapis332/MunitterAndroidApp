package com.munitter.android.web

import android.util.Log
import android.view.View
import android.webkit.WebView
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import com.munitter.android.device.AndroidDeviceScreenGeometryProvider
import org.json.JSONObject
import java.net.URI

internal class DeviceScreenGeometryOriginPolicy(
    internalHost: String,
) {
    private val internalHost = internalHost.lowercase()
    val allowedOrigin: String = "https://$internalHost"

    fun isAllowedDocument(rawUrl: String?): Boolean {
        val uri = runCatching { URI(rawUrl.orEmpty()) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals(internalHost, ignoreCase = true) &&
            uri.userInfo == null &&
            uri.port in setOf(-1, 443)
    }
}

internal class DeviceScreenGeometryDeliveryScriptBuilder(
    private val allowedOrigin: String,
) {
    fun build(payloadJson: String): String {
        val quotedOrigin = JSONObject.quote(allowedOrigin)
        val quotedPayload = JSONObject.quote(payloadJson)
        return """
            (() => {
              try {
                if (window.location.origin !== $quotedOrigin) return false;
                const payload = JSON.parse($quotedPayload);
                const receiver = window.MunitterDeviceScreenGeometry;
                if (!receiver || typeof receiver.setNativeGeometry !== 'function') {
                  window.__munitterPendingDeviceScreenGeometry = payload;
                  return false;
                }
                const accepted = receiver.setNativeGeometry(payload) === true;
                if (!accepted) {
                  window.__munitterPendingDeviceScreenGeometry = payload;
                  return false;
                }
                delete window.__munitterPendingDeviceScreenGeometry;
                return true;
              } catch (_) {
                return false;
              }
            })()
        """.trimIndent()
    }
}

internal class DeviceScreenGeometryDeliveryState {
    private var lastDeliveredKey: String? = null
    private var inFlightKey: String? = null

    fun begin(key: String, force: Boolean): Boolean {
        if (inFlightKey == key) return false
        if (!force && lastDeliveredKey == key) return false
        inFlightKey = key
        return true
    }

    fun finish(key: String, delivered: Boolean) {
        if (inFlightKey != key) return
        inFlightKey = null
        if (delivered) lastDeliveredKey = key
    }

    fun resetDocument() {
        lastDeliveredKey = null
        inFlightKey = null
    }
}

class DeviceScreenGeometryBridge(
    private val provider: AndroidDeviceScreenGeometryProvider,
    internalHost: String,
    private val developmentLoggingEnabled: Boolean,
) {
    private val originPolicy = DeviceScreenGeometryOriginPolicy(internalHost)
    private val scriptBuilder = DeviceScreenGeometryDeliveryScriptBuilder(
        originPolicy.allowedOrigin,
    )
    private val deliveryState = DeviceScreenGeometryDeliveryState()
    private var attachedWebView: WebView? = null
    private var refreshPosted = false
    private var forceNextDelivery = false
    private var nextRefreshReason = "attach"
    private var lastLoggedPayload: String? = null
    private var documentGeneration = 0L

    private val layoutChangeListener =
        View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (
                left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom
            ) {
                requestRefresh("layout")
            }
        }

    private val attachStateChangeListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            ViewCompat.requestApplyInsets(view)
            requestRefresh("view-attached")
        }

        override fun onViewDetachedFromWindow(view: View) = Unit
    }

    private val applyWindowInsetsListener = OnApplyWindowInsetsListener { _, insets ->
        requestRefresh("window-insets")
        insets
    }

    fun attach(webView: WebView) {
        detach()
        attachedWebView = webView
        webView.addOnLayoutChangeListener(layoutChangeListener)
        webView.addOnAttachStateChangeListener(attachStateChangeListener)
        ViewCompat.setOnApplyWindowInsetsListener(webView, applyWindowInsetsListener)
        if (webView.isAttachedToWindow) ViewCompat.requestApplyInsets(webView)
        requestRefresh("attach")
    }

    fun detach() {
        attachedWebView?.let { webView ->
            webView.removeOnLayoutChangeListener(layoutChangeListener)
            webView.removeOnAttachStateChangeListener(attachStateChangeListener)
            ViewCompat.setOnApplyWindowInsetsListener(webView, null)
        }
        attachedWebView = null
        refreshPosted = false
        forceNextDelivery = false
        nextRefreshReason = "detach"
        lastLoggedPayload = null
        documentGeneration = 0L
        deliveryState.resetDocument()
    }

    fun onDocumentStarted(webView: WebView) {
        if (webView !== attachedWebView) return
        documentGeneration += 1L
        deliveryState.resetDocument()
    }

    fun onDocumentAvailable(webView: WebView) {
        if (webView !== attachedWebView) return
        requestRefresh(reason = "document-available", forceDelivery = true)
    }

    fun onPageFinished(webView: WebView) {
        if (webView !== attachedWebView) return
        requestRefresh("page-finished")
    }

    fun onHostConfigurationChanged() {
        requestRefresh("configuration")
    }

    fun onHostResumed() {
        requestRefresh("resume")
    }

    private fun requestRefresh(
        reason: String,
        forceDelivery: Boolean = false,
    ) {
        val webView = attachedWebView ?: return
        nextRefreshReason = reason
        forceNextDelivery = forceNextDelivery || forceDelivery
        if (refreshPosted) return
        refreshPosted = true
        webView.post {
            if (webView !== attachedWebView) return@post
            refreshPosted = false
            val pendingForceDelivery = forceNextDelivery
            forceNextDelivery = false
            captureAndDeliver(webView, nextRefreshReason, pendingForceDelivery)
        }
    }

    private fun captureAndDeliver(
        webView: WebView,
        reason: String,
        forceDelivery: Boolean,
    ) {
        val geometry = provider.capture(webView) ?: return
        val payloadJson = geometry.toJsonString()
        if (developmentLoggingEnabled && payloadJson != lastLoggedPayload) {
            lastLoggedPayload = payloadJson
            Log.i(LOG_TAG, "reason=$reason ${geometry.logSummary()}")
        }

        val documentUrl = webView.url
        if (!originPolicy.isAllowedDocument(documentUrl)) return
        val deliveryKey = "$documentGeneration\n$documentUrl\n$payloadJson"
        if (!deliveryState.begin(deliveryKey, forceDelivery)) return

        val script = scriptBuilder.build(payloadJson)
        runCatching {
            webView.evaluateJavascript(script) { rawResult ->
                deliveryState.finish(
                    key = deliveryKey,
                    delivered = rawResult?.trim().equals("true", ignoreCase = true),
                )
            }
        }.onFailure { error ->
            deliveryState.finish(deliveryKey, delivered = false)
            if (developmentLoggingEnabled) {
                Log.w(LOG_TAG, "Native geometry delivery failed reason=$reason", error)
            }
        }
    }

    companion object {
        const val LOG_TAG = "DeviceScreenGeometry"
    }
}
