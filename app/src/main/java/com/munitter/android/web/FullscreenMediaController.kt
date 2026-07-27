package com.munitter.android.web

import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class FullscreenMediaController(
    private val activity: ComponentActivity,
    private val webViewProvider: () -> View?,
) {
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    val isShowing: Boolean
        get() = customView != null

    fun show(
        view: View?,
        callback: WebChromeClient.CustomViewCallback?,
    ) {
        if (view == null || customView != null) {
            callback?.onCustomViewHidden()
            return
        }

        customView = view
        customViewCallback = callback
        webViewProvider()?.visibility = View.GONE

        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        content.addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        WindowCompat.getInsetsController(activity.window, view).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    fun hide() {
        val view = customView ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        webViewProvider()?.visibility = View.VISIBLE

        WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }
}
