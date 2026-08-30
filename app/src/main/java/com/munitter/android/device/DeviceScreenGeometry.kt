package com.munitter.android.device

import org.json.JSONArray
import org.json.JSONObject

enum class DeviceScreenGeometryConfidence(val wireValue: String) {
    HIGH("high"),
    MEDIUM("medium"),
    LOW("low"),
}

data class DeviceScreenPoint(
    val x: Int,
    val y: Int,
)

data class DeviceScreenRadius(
    val x: Int,
    val y: Int,
)

data class DeviceScreenInsets(
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val left: Int = 0,
)

data class DeviceScreenRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int = (right - left).coerceAtLeast(0)
    val height: Int = (bottom - top).coerceAtLeast(0)
}

data class DeviceScreenViewport(
    val width: Int,
    val height: Int,
)

data class DeviceScreenOrientation(
    val type: String,
    val angle: Int,
)

data class DeviceScreenCorner(
    val radius: DeviceScreenRadius,
    val center: DeviceScreenPoint?,
    val source: String,
    val confidence: DeviceScreenGeometryConfidence,
)

data class DeviceScreenCorners(
    val topLeft: DeviceScreenCorner,
    val topRight: DeviceScreenCorner,
    val bottomRight: DeviceScreenCorner,
    val bottomLeft: DeviceScreenCorner,
)

data class DeviceScreenDisplayCutout(
    val safeInsets: DeviceScreenInsets = DeviceScreenInsets(),
    val waterfallInsets: DeviceScreenInsets = DeviceScreenInsets(),
    val boundingRects: List<DeviceScreenRect> = emptyList(),
)

data class DeviceScreenGeometry(
    val windowBounds: DeviceScreenRect,
    val screenPlacementBounds: DeviceScreenRect?,
    val surfaceBounds: DeviceScreenRect,
    val surfaceCoversWindow: Boolean,
    val viewport: DeviceScreenViewport,
    val orientation: DeviceScreenOrientation,
    val corners: DeviceScreenCorners,
    val safeAreaInsets: DeviceScreenInsets,
    val stableSafeAreaInsets: DeviceScreenInsets,
    val displayCutout: DeviceScreenDisplayCutout,
    val source: String,
    val confidence: DeviceScreenGeometryConfidence,
    val fallback: Boolean,
) {
    fun toJsonString(): String = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("platform", PLATFORM)
        put("coordinateSpace", COORDINATE_SPACE)
        put("windowBounds", windowBounds.toJson())
        put("screenPlacementBounds", screenPlacementBounds?.toJson() ?: JSONObject.NULL)
        put("surfaceBounds", surfaceBounds.toJson())
        put("surfaceCoversWindow", surfaceCoversWindow)
        put("viewport", viewport.toJson())
        put("orientation", orientation.toJson())
        put("corners", corners.toJson())
        put("safeAreaInsets", safeAreaInsets.toJson())
        put("stableSafeAreaInsets", stableSafeAreaInsets.toJson())
        put("displayCutout", displayCutout.toJson())
        put("source", source)
        put("confidence", confidence.wireValue)
        put("curve", CURVE)
        put("fallback", fallback)
    }.toString()

    fun logSummary(): String =
        "source=$source fallback=$fallback confidence=${confidence.wireValue} " +
            "bounds=${windowBounds.left},${windowBounds.top},${windowBounds.right},${windowBounds.bottom} " +
            "screenPlacement=${screenPlacementBounds?.let { "${it.left},${it.top},${it.right},${it.bottom}" } ?: "null"} " +
            "surface=${surfaceBounds.left},${surfaceBounds.top},${surfaceBounds.right},${surfaceBounds.bottom} " +
            "surfaceCoversWindow=$surfaceCoversWindow " +
            "orientation=${orientation.type}@${orientation.angle} " +
            "topLeft=${corners.topLeft.logValue()} " +
            "topRight=${corners.topRight.logValue()} " +
            "bottomRight=${corners.bottomRight.logValue()} " +
            "bottomLeft=${corners.bottomLeft.logValue()}"

    companion object {
        const val SCHEMA_VERSION = 1
        const val PLATFORM = "android"
        const val COORDINATE_SPACE = "application-window-physical-px"
        const val CURVE = "circular"
    }
}

data class AndroidRoundedCornerInput(
    val radius: Int,
    val center: DeviceScreenPoint,
)

enum class AndroidRoundedCornerStatus {
    PRESENT,
    NATIVE_ABSENT,
    OUTSIDE_OR_UNAVAILABLE,
    API_UNSUPPORTED,
}

internal fun resolveAndroidRoundedCornerStatus(
    hasNativeGeometry: Boolean,
    windowContainsDisplayCorner: Boolean,
): AndroidRoundedCornerStatus = when {
    hasNativeGeometry -> AndroidRoundedCornerStatus.PRESENT
    windowContainsDisplayCorner -> AndroidRoundedCornerStatus.NATIVE_ABSENT
    else -> AndroidRoundedCornerStatus.OUTSIDE_OR_UNAVAILABLE
}

data class AndroidRoundedCornerObservation(
    val status: AndroidRoundedCornerStatus,
    val geometry: AndroidRoundedCornerInput? = null,
) {
    init {
        require((status == AndroidRoundedCornerStatus.PRESENT) == (geometry != null)) {
            "Only a present rounded corner may contain geometry"
        }
    }
}

data class AndroidRoundedCornersInput(
    val topLeft: AndroidRoundedCornerObservation,
    val topRight: AndroidRoundedCornerObservation,
    val bottomRight: AndroidRoundedCornerObservation,
    val bottomLeft: AndroidRoundedCornerObservation,
) {
    val observations: List<AndroidRoundedCornerObservation>
        get() = listOf(topLeft, topRight, bottomRight, bottomLeft)
}

data class AndroidDeviceScreenGeometryInput(
    val windowBounds: DeviceScreenRect,
    val screenPlacementBounds: DeviceScreenRect?,
    val surfaceBounds: DeviceScreenRect,
    val surfaceCoversWindow: Boolean,
    val viewport: DeviceScreenViewport,
    val displayRotationDegrees: Int,
    val roundedCornerApiAvailable: Boolean,
    val roundedCorners: AndroidRoundedCornersInput,
    val safeAreaInsets: DeviceScreenInsets,
    val stableSafeAreaInsets: DeviceScreenInsets,
    val displayCutout: DeviceScreenDisplayCutout,
)

class DeviceScreenGeometryResolver {
    fun resolve(input: AndroidDeviceScreenGeometryInput): DeviceScreenGeometry {
        val observations = input.roundedCorners.observations
        val unavailableCount = observations.count {
            it.status == AndroidRoundedCornerStatus.OUTSIDE_OR_UNAVAILABLE
        }
        val unsupportedCount = observations.count {
            it.status == AndroidRoundedCornerStatus.API_UNSUPPORTED
        }
        val surfaceFallback = !input.surfaceCoversWindow
        val fallback = surfaceFallback || unavailableCount > 0 || unsupportedCount > 0
        val source = when {
            surfaceFallback -> SURFACE_MISMATCH_FALLBACK_SOURCE
            unavailableCount == CORNER_COUNT -> if (input.roundedCornerApiAvailable) {
                UNAVAILABLE_FALLBACK_SOURCE
            } else {
                LEGACY_FALLBACK_SOURCE
            }
            unsupportedCount == CORNER_COUNT -> LEGACY_FALLBACK_SOURCE
            unavailableCount > 0 || unsupportedCount > 0 -> PARTIAL_NATIVE_SOURCE
            else -> NATIVE_SOURCE
        }
        val confidence = when {
            surfaceFallback -> DeviceScreenGeometryConfidence.LOW
            unavailableCount == 0 && unsupportedCount == 0 -> DeviceScreenGeometryConfidence.HIGH
            unavailableCount + unsupportedCount == CORNER_COUNT -> DeviceScreenGeometryConfidence.LOW
            else -> DeviceScreenGeometryConfidence.MEDIUM
        }

        val corners = if (surfaceFallback) {
            DeviceScreenCorners(
                topLeft = surfaceFallbackCorner(),
                topRight = surfaceFallbackCorner(),
                bottomRight = surfaceFallbackCorner(),
                bottomLeft = surfaceFallbackCorner(),
            )
        } else {
            DeviceScreenCorners(
                topLeft = resolveCorner(input.roundedCorners.topLeft),
                topRight = resolveCorner(input.roundedCorners.topRight),
                bottomRight = resolveCorner(input.roundedCorners.bottomRight),
                bottomLeft = resolveCorner(input.roundedCorners.bottomLeft),
            )
        }

        return DeviceScreenGeometry(
            windowBounds = input.windowBounds,
            screenPlacementBounds = input.screenPlacementBounds,
            surfaceBounds = input.surfaceBounds,
            surfaceCoversWindow = input.surfaceCoversWindow,
            viewport = input.viewport,
            orientation = resolveOrientation(
                width = input.windowBounds.width,
                height = input.windowBounds.height,
                rotationDegrees = input.displayRotationDegrees,
            ),
            corners = corners,
            safeAreaInsets = input.safeAreaInsets.nonNegative(),
            stableSafeAreaInsets = input.stableSafeAreaInsets.nonNegative(),
            displayCutout = input.displayCutout,
            source = source,
            confidence = confidence,
            fallback = fallback,
        )
    }

    private fun resolveCorner(observation: AndroidRoundedCornerObservation): DeviceScreenCorner {
        return when (observation.status) {
            AndroidRoundedCornerStatus.PRESENT -> {
                val input = checkNotNull(observation.geometry)
                val radius = input.radius.coerceAtLeast(0)
                DeviceScreenCorner(
                    radius = DeviceScreenRadius(radius, radius),
                    center = input.center,
                    source = NATIVE_SOURCE,
                    confidence = DeviceScreenGeometryConfidence.HIGH,
                )
            }
            AndroidRoundedCornerStatus.NATIVE_ABSENT -> DeviceScreenCorner(
                radius = DeviceScreenRadius(0, 0),
                center = null,
                source = NATIVE_ABSENT_SOURCE,
                confidence = DeviceScreenGeometryConfidence.HIGH,
            )
            AndroidRoundedCornerStatus.OUTSIDE_OR_UNAVAILABLE,
            AndroidRoundedCornerStatus.API_UNSUPPORTED,
            -> genericFallbackCorner()
        }
    }

    private fun genericFallbackCorner() = DeviceScreenCorner(
        radius = DeviceScreenRadius(0, 0),
        center = null,
        source = CORNER_FALLBACK_SOURCE,
        confidence = DeviceScreenGeometryConfidence.LOW,
    )

    private fun surfaceFallbackCorner() = DeviceScreenCorner(
        radius = DeviceScreenRadius(0, 0),
        center = null,
        source = SURFACE_MISMATCH_FALLBACK_SOURCE,
        confidence = DeviceScreenGeometryConfidence.LOW,
    )

    internal fun resolveOrientation(
        width: Int,
        height: Int,
        rotationDegrees: Int,
    ): DeviceScreenOrientation {
        val angle = ((rotationDegrees % FULL_ROTATION) + FULL_ROTATION) % FULL_ROTATION
        val isPortrait = height >= width
        val type = if (isPortrait) {
            if (angle == 180) "portrait-secondary" else "portrait-primary"
        } else {
            if (angle == 270) "landscape-secondary" else "landscape-primary"
        }
        return DeviceScreenOrientation(type = type, angle = angle)
    }

    companion object {
        const val NATIVE_SOURCE = "android-window-insets-rounded-corner"
        const val PARTIAL_NATIVE_SOURCE = "android-window-insets-rounded-corner-partial"
        const val NATIVE_ABSENT_SOURCE = "android-window-insets-rounded-corner-absent"
        const val UNAVAILABLE_FALLBACK_SOURCE =
            "android-window-insets-rounded-corner-unavailable-fallback"
        const val LEGACY_FALLBACK_SOURCE = "android-api-below-31-generic-fallback"
        const val CORNER_FALLBACK_SOURCE = "android-generic-zero-radius-fallback"
        const val SURFACE_MISMATCH_FALLBACK_SOURCE =
            "android-webview-does-not-cover-window-fallback"
        private const val CORNER_COUNT = 4
        private const val FULL_ROTATION = 360
    }
}

private fun DeviceScreenRect.toJson(): JSONObject = JSONObject().apply {
    put("left", left)
    put("top", top)
    put("right", right)
    put("bottom", bottom)
    put("width", width)
    put("height", height)
}

private fun DeviceScreenViewport.toJson(): JSONObject = JSONObject().apply {
    put("width", width)
    put("height", height)
}

private fun DeviceScreenOrientation.toJson(): JSONObject = JSONObject().apply {
    put("type", type)
    put("angle", angle)
}

private fun DeviceScreenCorners.toJson(): JSONObject = JSONObject().apply {
    put("topLeft", topLeft.toJson())
    put("topRight", topRight.toJson())
    put("bottomRight", bottomRight.toJson())
    put("bottomLeft", bottomLeft.toJson())
}

private fun DeviceScreenCorner.toJson(): JSONObject = JSONObject().apply {
    put("radius", radius.toJson())
    put("center", center?.toJson() ?: JSONObject.NULL)
    put("source", source)
    put("confidence", confidence.wireValue)
}

private fun DeviceScreenRadius.toJson(): JSONObject = JSONObject().apply {
    put("x", x)
    put("y", y)
}

private fun DeviceScreenPoint.toJson(): JSONObject = JSONObject().apply {
    put("x", x)
    put("y", y)
}

private fun DeviceScreenInsets.toJson(): JSONObject = JSONObject().apply {
    put("top", top)
    put("right", right)
    put("bottom", bottom)
    put("left", left)
}

private fun DeviceScreenDisplayCutout.toJson(): JSONObject = JSONObject().apply {
    put("safeInsets", safeInsets.toJson())
    put("waterfallInsets", waterfallInsets.toJson())
    put(
        "boundingRects",
        JSONArray().apply { boundingRects.forEach { put(it.toJson()) } },
    )
}

private fun DeviceScreenCorner.logValue(): String =
    "r=${radius.x}/${radius.y},c=${center?.let { "${it.x}/${it.y}" } ?: "null"}," +
        "source=$source"

private fun DeviceScreenInsets.nonNegative(): DeviceScreenInsets = DeviceScreenInsets(
    top = top.coerceAtLeast(0),
    right = right.coerceAtLeast(0),
    bottom = bottom.coerceAtLeast(0),
    left = left.coerceAtLeast(0),
)
