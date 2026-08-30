package com.munitter.android.device

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceScreenGeometryResolverTest {
    private val resolver = DeviceScreenGeometryResolver()

    @Test
    fun `api 31 rounded corners remain independent native geometry`() {
        val geometry = resolver.resolve(
            input(
                corners = AndroidRoundedCornersInput(
                    topLeft = presentCorner(radius = 113, x = 113, y = 113),
                    topRight = presentCorner(radius = 114, x = 966, y = 114),
                    bottomRight = presentCorner(radius = 115, x = 965, y = 2_225),
                    bottomLeft = presentCorner(radius = 116, x = 116, y = 2_224),
                ),
            ),
        )

        assertFalse(geometry.fallback)
        assertEquals(DeviceScreenGeometryResolver.NATIVE_SOURCE, geometry.source)
        assertEquals(DeviceScreenGeometryConfidence.HIGH, geometry.confidence)
        assertEquals(DeviceScreenRadius(113, 113), geometry.corners.topLeft.radius)
        assertEquals(DeviceScreenRadius(114, 114), geometry.corners.topRight.radius)
        assertEquals(DeviceScreenRadius(115, 115), geometry.corners.bottomRight.radius)
        assertEquals(DeviceScreenRadius(116, 116), geometry.corners.bottomLeft.radius)
        assertEquals(DeviceScreenPoint(965, 2_225), geometry.corners.bottomRight.center)
    }

    @Test
    fun `missing api 31 corners use explicit low confidence zero fallback only where missing`() {
        val geometry = resolver.resolve(
            input(
                corners = AndroidRoundedCornersInput(
                    topLeft = presentCorner(radius = 113, x = 113, y = 113),
                    topRight = unavailableCorner(),
                    bottomRight = unavailableCorner(),
                    bottomLeft = unavailableCorner(),
                ),
            ),
        )

        assertTrue(geometry.fallback)
        assertEquals(DeviceScreenGeometryResolver.PARTIAL_NATIVE_SOURCE, geometry.source)
        assertEquals(DeviceScreenGeometryConfidence.MEDIUM, geometry.confidence)
        assertEquals(DeviceScreenGeometryConfidence.HIGH, geometry.corners.topLeft.confidence)
        assertEquals(DeviceScreenRadius(0, 0), geometry.corners.topRight.radius)
        assertEquals(DeviceScreenGeometryConfidence.LOW, geometry.corners.topRight.confidence)
        assertEquals(
            DeviceScreenGeometryResolver.CORNER_FALLBACK_SOURCE,
            geometry.corners.topRight.source,
        )
        assertNull(geometry.corners.topRight.center)
    }

    @Test
    fun `api 30 fallback does not reinterpret safe area or cutout as corner radius`() {
        val geometry = resolver.resolve(
            input(
                roundedCornerApiAvailable = false,
                corners = unsupportedCorners(),
                safeAreaInsets = DeviceScreenInsets(top = 97, bottom = 135),
                displayCutout = DeviceScreenDisplayCutout(
                    safeInsets = DeviceScreenInsets(top = 82),
                    boundingRects = listOf(DeviceScreenRect(511, 24, 569, 82)),
                ),
            ),
        )

        assertTrue(geometry.fallback)
        assertEquals(DeviceScreenGeometryResolver.LEGACY_FALLBACK_SOURCE, geometry.source)
        assertEquals(DeviceScreenGeometryConfidence.LOW, geometry.confidence)
        assertEquals(DeviceScreenRadius(0, 0), geometry.corners.topLeft.radius)
        assertEquals(97, geometry.safeAreaInsets.top)
        assertEquals(82, geometry.displayCutout.safeInsets.top)
        assertEquals(1, geometry.displayCutout.boundingRects.size)
    }

    @Test
    fun `api 31 authoritative absence is a high confidence native square corner`() {
        val geometry = resolver.resolve(input(corners = nativeAbsentCorners()))

        assertFalse(geometry.fallback)
        assertEquals(DeviceScreenGeometryResolver.NATIVE_SOURCE, geometry.source)
        assertEquals(DeviceScreenGeometryConfidence.HIGH, geometry.confidence)
        assertEquals(DeviceScreenRadius(0, 0), geometry.corners.topLeft.radius)
        assertEquals(
            DeviceScreenGeometryResolver.NATIVE_ABSENT_SOURCE,
            geometry.corners.topLeft.source,
        )
        assertEquals(DeviceScreenGeometryConfidence.HIGH, geometry.corners.bottomRight.confidence)
    }

    @Test
    fun `api 31 null observation distinguishes native absence from window outside`() {
        assertEquals(
            AndroidRoundedCornerStatus.NATIVE_ABSENT,
            resolveAndroidRoundedCornerStatus(
                hasNativeGeometry = false,
                windowContainsDisplayCorner = true,
            ),
        )
        assertEquals(
            AndroidRoundedCornerStatus.OUTSIDE_OR_UNAVAILABLE,
            resolveAndroidRoundedCornerStatus(
                hasNativeGeometry = false,
                windowContainsDisplayCorner = false,
            ),
        )
        assertEquals(
            AndroidRoundedCornerStatus.PRESENT,
            resolveAndroidRoundedCornerStatus(
                hasNativeGeometry = true,
                windowContainsDisplayCorner = false,
            ),
        )
    }

    @Test
    fun `partial webview surface forces explicit low confidence geometry`() {
        val geometry = resolver.resolve(
            input(
                surfaceBounds = DeviceScreenRect(0, 97, 1_080, 2_205),
                surfaceCoversWindow = false,
                corners = allPresentCorners(),
            ),
        )

        assertTrue(geometry.fallback)
        assertFalse(geometry.surfaceCoversWindow)
        assertEquals(
            DeviceScreenGeometryResolver.SURFACE_MISMATCH_FALLBACK_SOURCE,
            geometry.source,
        )
        assertEquals(DeviceScreenGeometryConfidence.LOW, geometry.confidence)
        assertEquals(DeviceScreenRadius(0, 0), geometry.corners.topLeft.radius)
        assertEquals(
            DeviceScreenGeometryResolver.SURFACE_MISMATCH_FALLBACK_SOURCE,
            geometry.corners.topLeft.source,
        )
    }

    @Test
    fun `application window coordinates remain local while screen placement remains raw`() {
        val geometry = resolver.resolve(
            input(
                bounds = DeviceScreenRect(0, 0, 1_080, 1_170),
                screenPlacementBounds = DeviceScreenRect(0, 1_170, 1_080, 2_340),
                surfaceBounds = DeviceScreenRect(0, 0, 1_080, 1_170),
                viewport = DeviceScreenViewport(1_080, 1_170),
                corners = nativeAbsentCorners(),
            ),
        )

        assertEquals(0, geometry.windowBounds.left)
        assertEquals(0, geometry.windowBounds.top)
        assertEquals(1_170, geometry.windowBounds.height)
        assertEquals(1_170, geometry.screenPlacementBounds?.top)
        assertEquals(DeviceScreenRect(0, 0, 1_080, 1_170), geometry.surfaceBounds)
    }

    @Test
    fun `orientation follows current bounds and display rotation`() {
        val portrait = resolver.resolve(input(rotationDegrees = 0))
        val landscape = resolver.resolve(
            input(
                bounds = DeviceScreenRect(0, 0, 2_340, 1_080),
                viewport = DeviceScreenViewport(2_340, 1_080),
                rotationDegrees = 90,
            ),
        )
        val reverseLandscape = resolver.resolve(
            input(
                bounds = DeviceScreenRect(0, 0, 2_340, 1_080),
                viewport = DeviceScreenViewport(2_340, 1_080),
                rotationDegrees = 270,
            ),
        )

        assertEquals(DeviceScreenOrientation("portrait-primary", 0), portrait.orientation)
        assertEquals(DeviceScreenOrientation("landscape-primary", 90), landscape.orientation)
        assertEquals(
            DeviceScreenOrientation("landscape-secondary", 270),
            reverseLandscape.orientation,
        )
    }

    @Test
    fun `serialization emits the versioned native web contract`() {
        val geometry = resolver.resolve(
            input(
                corners = AndroidRoundedCornersInput(
                    topLeft = presentCorner(113, 113, 113),
                    topRight = presentCorner(113, 967, 113),
                    bottomRight = presentCorner(113, 967, 2_227),
                    bottomLeft = presentCorner(113, 113, 2_227),
                ),
                displayCutout = DeviceScreenDisplayCutout(
                    safeInsets = DeviceScreenInsets(top = 82),
                    waterfallInsets = DeviceScreenInsets(bottom = 1),
                    boundingRects = listOf(DeviceScreenRect(511, 24, 569, 82)),
                ),
            ),
        )

        val json = JSONObject(geometry.toJsonString())
        assertEquals(1, json.getInt("schemaVersion"))
        assertEquals("android", json.getString("platform"))
        assertEquals(
            "application-window-physical-px",
            json.getString("coordinateSpace"),
        )
        assertEquals(1_080, json.getJSONObject("windowBounds").getInt("width"))
        assertEquals(0, json.getJSONObject("windowBounds").getInt("left"))
        assertEquals(0, json.getJSONObject("surfaceBounds").getInt("top"))
        assertTrue(json.getBoolean("surfaceCoversWindow"))
        assertEquals(2_340, json.getJSONObject("viewport").getInt("height"))
        assertEquals(
            113,
            json.getJSONObject("corners")
                .getJSONObject("topLeft")
                .getJSONObject("radius")
                .getInt("x"),
        )
        assertEquals(
            967,
            json.getJSONObject("corners")
                .getJSONObject("bottomRight")
                .getJSONObject("center")
                .getInt("x"),
        )
        assertEquals(
            82,
            json.getJSONObject("displayCutout")
                .getJSONObject("safeInsets")
                .getInt("top"),
        )
        assertEquals(
            58,
            json.getJSONObject("displayCutout")
                .getJSONArray("boundingRects")
                .getJSONObject(0)
                .getInt("width"),
        )
        assertEquals("circular", json.getString("curve"))
        assertFalse(json.getBoolean("fallback"))
    }

    private fun input(
        bounds: DeviceScreenRect = DeviceScreenRect(0, 0, 1_080, 2_340),
        screenPlacementBounds: DeviceScreenRect? = DeviceScreenRect(0, 0, 1_080, 2_340),
        surfaceBounds: DeviceScreenRect = bounds,
        surfaceCoversWindow: Boolean = true,
        viewport: DeviceScreenViewport = DeviceScreenViewport(1_080, 2_340),
        rotationDegrees: Int = 0,
        roundedCornerApiAvailable: Boolean = true,
        corners: AndroidRoundedCornersInput = nativeAbsentCorners(),
        safeAreaInsets: DeviceScreenInsets = DeviceScreenInsets(top = 97, bottom = 135),
        displayCutout: DeviceScreenDisplayCutout = DeviceScreenDisplayCutout(),
    ) = AndroidDeviceScreenGeometryInput(
        windowBounds = bounds,
        screenPlacementBounds = screenPlacementBounds,
        surfaceBounds = surfaceBounds,
        surfaceCoversWindow = surfaceCoversWindow,
        viewport = viewport,
        displayRotationDegrees = rotationDegrees,
        roundedCornerApiAvailable = roundedCornerApiAvailable,
        roundedCorners = corners,
        safeAreaInsets = safeAreaInsets,
        stableSafeAreaInsets = safeAreaInsets,
        displayCutout = displayCutout,
    )

    private fun presentCorner(radius: Int, x: Int, y: Int) =
        AndroidRoundedCornerObservation(
            status = AndroidRoundedCornerStatus.PRESENT,
            geometry = AndroidRoundedCornerInput(
                radius = radius,
                center = DeviceScreenPoint(x, y),
            ),
        )

    private fun unavailableCorner() = AndroidRoundedCornerObservation(
        AndroidRoundedCornerStatus.OUTSIDE_OR_UNAVAILABLE,
    )

    private fun nativeAbsentCorners(): AndroidRoundedCornersInput {
        val absent = AndroidRoundedCornerObservation(AndroidRoundedCornerStatus.NATIVE_ABSENT)
        return AndroidRoundedCornersInput(absent, absent, absent, absent)
    }

    private fun unsupportedCorners(): AndroidRoundedCornersInput {
        val unsupported = AndroidRoundedCornerObservation(AndroidRoundedCornerStatus.API_UNSUPPORTED)
        return AndroidRoundedCornersInput(unsupported, unsupported, unsupported, unsupported)
    }

    private fun allPresentCorners() = AndroidRoundedCornersInput(
        topLeft = presentCorner(113, 113, 113),
        topRight = presentCorner(113, 967, 113),
        bottomRight = presentCorner(113, 967, 2_227),
        bottomLeft = presentCorner(113, 113, 2_227),
    )
}
