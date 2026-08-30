package com.munitter.android.device

import android.app.Activity
import android.graphics.Rect
import android.os.Build
import android.view.RoundedCorner
import android.view.Surface
import android.view.WindowInsets
import android.webkit.WebView
import androidx.annotation.RequiresApi
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat

class AndroidDeviceScreenGeometryProvider(
    private val activity: Activity,
    private val resolver: DeviceScreenGeometryResolver = DeviceScreenGeometryResolver(),
) {
    fun capture(webView: WebView): DeviceScreenGeometry? {
        if (!webView.isAttachedToWindow || webView.width <= 0 || webView.height <= 0) {
            return null
        }
        val snapshot = currentWindowSnapshot(webView) ?: return null
        val platformInsets = snapshot.insets
        val localWindowBounds = Rect(0, 0, snapshot.screenBounds.width(), snapshot.screenBounds.height())
        if (localWindowBounds.width() <= 0 || localWindowBounds.height() <= 0) return null
        val surfaceBounds = surfaceBoundsInWindow(webView)
        val surfaceCoversWindow = surfaceCoversWindow(surfaceBounds, localWindowBounds)

        val compatInsets = WindowInsetsCompat.toWindowInsetsCompat(platformInsets, webView)
        val safeTypes = WindowInsetsCompat.Type.systemBars() or
            WindowInsetsCompat.Type.displayCutout()
        val safeAreaInsets = compatInsets.getInsets(safeTypes).toDeviceScreenInsets()
        val stableSafeAreaInsets = runCatching {
            compatInsets.getInsetsIgnoringVisibility(safeTypes).toDeviceScreenInsets()
        }.getOrDefault(safeAreaInsets)
        val roundedCornerApiAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val roundedCorners = if (roundedCornerApiAvailable) {
            roundedCorners(
                insets = platformInsets,
                screenBounds = snapshot.screenBounds,
                maximumScreenBounds = snapshot.maximumScreenBounds,
            )
        } else {
            unsupportedRoundedCorners()
        }

        return resolver.resolve(
            AndroidDeviceScreenGeometryInput(
                windowBounds = localWindowBounds.toDeviceScreenRect(),
                screenPlacementBounds = snapshot.screenBounds.toDeviceScreenRect(),
                surfaceBounds = surfaceBounds.toDeviceScreenRect(),
                surfaceCoversWindow = surfaceCoversWindow,
                viewport = DeviceScreenViewport(webView.width, webView.height),
                displayRotationDegrees = displayRotationDegrees(webView),
                roundedCornerApiAvailable = roundedCornerApiAvailable,
                roundedCorners = roundedCorners,
                safeAreaInsets = safeAreaInsets,
                stableSafeAreaInsets = stableSafeAreaInsets,
                displayCutout = displayCutout(platformInsets),
            ),
        )
    }

    private fun currentWindowSnapshot(webView: WebView): WindowSnapshot? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            currentWindowSnapshotApi30()
        } else {
            val insets = webView.rootWindowInsets ?: return null
            val root = webView.rootView
            WindowSnapshot(
                screenBounds = Rect(0, 0, root.width, root.height),
                maximumScreenBounds = Rect(0, 0, root.width, root.height),
                insets = insets,
            )
        }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun currentWindowSnapshotApi30(): WindowSnapshot {
        val windowManager = activity.windowManager
        val currentMetrics = windowManager.currentWindowMetrics
        return WindowSnapshot(
            screenBounds = Rect(currentMetrics.bounds),
            maximumScreenBounds = Rect(windowManager.maximumWindowMetrics.bounds),
            insets = currentMetrics.windowInsets,
        )
    }

    private fun surfaceBoundsInWindow(webView: WebView): Rect {
        val location = IntArray(2)
        webView.getLocationInWindow(location)
        return Rect(
            location[0],
            location[1],
            location[0] + webView.width,
            location[1] + webView.height,
        )
    }

    private fun surfaceCoversWindow(surfaceBounds: Rect, windowBounds: Rect): Boolean =
        kotlin.math.abs(surfaceBounds.left - windowBounds.left) <= SURFACE_TOLERANCE_PX &&
            kotlin.math.abs(surfaceBounds.top - windowBounds.top) <= SURFACE_TOLERANCE_PX &&
            kotlin.math.abs(surfaceBounds.right - windowBounds.right) <= SURFACE_TOLERANCE_PX &&
            kotlin.math.abs(surfaceBounds.bottom - windowBounds.bottom) <= SURFACE_TOLERANCE_PX

    private fun displayRotationDegrees(webView: WebView): Int {
        val rotation = webView.display?.rotation
            ?: @Suppress("DEPRECATION") activity.windowManager.defaultDisplay.rotation
        return when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun roundedCorners(
        insets: WindowInsets,
        screenBounds: Rect,
        maximumScreenBounds: Rect,
    ): AndroidRoundedCornersInput =
        AndroidRoundedCornersInput(
            topLeft = insets.roundedCornerObservation(
                RoundedCorner.POSITION_TOP_LEFT,
                windowContainsDisplayCorner(screenBounds, maximumScreenBounds, RoundedCorner.POSITION_TOP_LEFT),
            ),
            topRight = insets.roundedCornerObservation(
                RoundedCorner.POSITION_TOP_RIGHT,
                windowContainsDisplayCorner(screenBounds, maximumScreenBounds, RoundedCorner.POSITION_TOP_RIGHT),
            ),
            bottomRight = insets.roundedCornerObservation(
                RoundedCorner.POSITION_BOTTOM_RIGHT,
                windowContainsDisplayCorner(screenBounds, maximumScreenBounds, RoundedCorner.POSITION_BOTTOM_RIGHT),
            ),
            bottomLeft = insets.roundedCornerObservation(
                RoundedCorner.POSITION_BOTTOM_LEFT,
                windowContainsDisplayCorner(screenBounds, maximumScreenBounds, RoundedCorner.POSITION_BOTTOM_LEFT),
            ),
        )

    @RequiresApi(Build.VERSION_CODES.S)
    private fun WindowInsets.roundedCornerObservation(
        position: Int,
        windowContainsDisplayCorner: Boolean,
    ): AndroidRoundedCornerObservation {
        val corner = getRoundedCorner(position)
        if (corner != null) {
            return AndroidRoundedCornerObservation(
                status = resolveAndroidRoundedCornerStatus(
                    hasNativeGeometry = true,
                    windowContainsDisplayCorner = windowContainsDisplayCorner,
                ),
                geometry = AndroidRoundedCornerInput(
                    radius = corner.radius,
                    center = DeviceScreenPoint(corner.center.x, corner.center.y),
                ),
            )
        }
        return AndroidRoundedCornerObservation(
            status = resolveAndroidRoundedCornerStatus(
                hasNativeGeometry = false,
                windowContainsDisplayCorner = windowContainsDisplayCorner,
            ),
        )
    }

    private fun windowContainsDisplayCorner(
        windowBounds: Rect,
        maximumBounds: Rect,
        position: Int,
    ): Boolean = when (position) {
        RoundedCorner.POSITION_TOP_LEFT ->
            windowBounds.left <= maximumBounds.left && windowBounds.top <= maximumBounds.top
        RoundedCorner.POSITION_TOP_RIGHT ->
            windowBounds.right >= maximumBounds.right && windowBounds.top <= maximumBounds.top
        RoundedCorner.POSITION_BOTTOM_RIGHT ->
            windowBounds.right >= maximumBounds.right && windowBounds.bottom >= maximumBounds.bottom
        RoundedCorner.POSITION_BOTTOM_LEFT ->
            windowBounds.left <= maximumBounds.left && windowBounds.bottom >= maximumBounds.bottom
        else -> false
    }

    private fun unsupportedRoundedCorners(): AndroidRoundedCornersInput {
        val unsupported = AndroidRoundedCornerObservation(
            AndroidRoundedCornerStatus.API_UNSUPPORTED,
        )
        return AndroidRoundedCornersInput(unsupported, unsupported, unsupported, unsupported)
    }

    private fun displayCutout(insets: WindowInsets): DeviceScreenDisplayCutout {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return DeviceScreenDisplayCutout()
        }
        val cutout = insets.displayCutout ?: return DeviceScreenDisplayCutout()
        val waterfall = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            cutout.waterfallInsets.let { inset ->
                DeviceScreenInsets(
                    top = inset.top,
                    right = inset.right,
                    bottom = inset.bottom,
                    left = inset.left,
                )
            }
        } else {
            DeviceScreenInsets()
        }
        return DeviceScreenDisplayCutout(
            safeInsets = DeviceScreenInsets(
                top = cutout.safeInsetTop,
                right = cutout.safeInsetRight,
                bottom = cutout.safeInsetBottom,
                left = cutout.safeInsetLeft,
            ),
            waterfallInsets = waterfall,
            boundingRects = cutout.boundingRects.map(Rect::toDeviceScreenRect),
        )
    }

    private data class WindowSnapshot(
        val screenBounds: Rect,
        val maximumScreenBounds: Rect,
        val insets: WindowInsets,
    )

    private companion object {
        const val SURFACE_TOLERANCE_PX = 1
    }
}

private fun Insets.toDeviceScreenInsets(): DeviceScreenInsets = DeviceScreenInsets(
    top = top,
    right = right,
    bottom = bottom,
    left = left,
)

private fun Rect.toDeviceScreenRect(): DeviceScreenRect = DeviceScreenRect(
    left = left,
    top = top,
    right = right,
    bottom = bottom,
)
