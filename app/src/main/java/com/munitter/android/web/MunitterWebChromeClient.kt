package com.munitter.android.web

import android.os.Message
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.munitter.android.media.FileChooserCoordinator
import com.munitter.android.navigation.NavigationCoordinator

class MunitterWebChromeClient(
    private val fileChooser: FileChooserCoordinator,
    private val permissions: WebPermissionCoordinator,
    private val fullscreen: FullscreenMediaController,
    private val navigationCoordinator: NavigationCoordinator,
    private val onProgressChanged: (Int) -> Unit,
) : WebChromeClient() {

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        onProgressChanged(newProgress.coerceIn(0, 100))
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<android.net.Uri>>,
        fileChooserParams: FileChooserParams,
    ): Boolean = fileChooser.showFileChooser(filePathCallback, fileChooserParams)

    override fun onPermissionRequest(request: PermissionRequest) {
        permissions.onPermissionRequest(request)
    }

    override fun onPermissionRequestCanceled(request: PermissionRequest) {
        permissions.onPermissionRequestCanceled(request)
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String?,
        callback: GeolocationPermissions.Callback?,
    ) {
        callback?.invoke(origin, false, false)
    }

    override fun onShowCustomView(
        view: android.view.View?,
        callback: CustomViewCallback?,
    ) {
        fullscreen.show(view, callback)
    }

    override fun onHideCustomView() {
        fullscreen.hide()
    }

    override fun onCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message,
    ): Boolean {
        if (!isUserGesture) return false

        val popup = WebView(view.context)
        var handled = false
        fun routeAndDestroy(url: String?) {
            if (handled || url.isNullOrBlank() || url == "about:blank") return
            handled = true
            popup.stopLoading()
            navigationCoordinator.openPopup(url)
            popup.destroy()
        }

        popup.settings.javaScriptEnabled = false
        popup.webViewClient = object : android.webkit.WebViewClient() {
            override fun shouldOverrideUrlLoading(
                popupView: WebView,
                request: android.webkit.WebResourceRequest,
            ): Boolean {
                routeAndDestroy(request.url.toString())
                return true
            }

            override fun onPageStarted(
                popupView: WebView,
                url: String?,
                favicon: android.graphics.Bitmap?,
            ) {
                routeAndDestroy(url)
            }
        }

        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        transport.webView = popup
        resultMsg.sendToTarget()
        return true
    }
}
