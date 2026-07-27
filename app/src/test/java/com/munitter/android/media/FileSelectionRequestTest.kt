package com.munitter.android.media

import android.webkit.WebChromeClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileSelectionRequestTest {
    @Test
    fun `missing MIME types produce a visual wildcard single-selection request`() {
        val request = FileSelectionRequest.from(
            rawMimeTypes = null,
            chooserMode = WebChromeClient.FileChooserParams.MODE_OPEN,
        )

        assertEquals(emptyList<String>(), request.mimeTypes)
        assertFalse(request.allowMultiple)
        assertTrue(request.acceptsImages)
        assertTrue(request.acceptsVideos)
        assertTrue(request.isVisualMedia)
        assertFalse(request.supportsOnlyImages)
        assertFalse(request.supportsOnlyVideos)
    }

    @Test
    fun `MIME types are split normalized de-duplicated and kept in encounter order`() {
        val request = FileSelectionRequest.from(
            rawMimeTypes = arrayOf(
                " IMAGE/PNG, video/MP4 ",
                "image/png",
                " ",
                "Application/PDF",
            ),
            chooserMode = WebChromeClient.FileChooserParams.MODE_OPEN,
        )

        assertEquals(
            listOf("image/png", "video/mp4", "application/pdf"),
            request.mimeTypes,
        )
    }

    @Test
    fun `only the multiple-open chooser mode enables multiple selection`() {
        assertTrue(
            request(
                "image/*",
                chooserMode = WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE,
            ).allowMultiple,
        )
        assertFalse(
            request(
                "image/*",
                chooserMode = WebChromeClient.FileChooserParams.MODE_OPEN,
            ).allowMultiple,
        )
        assertFalse(request("image/*", chooserMode = Int.MAX_VALUE).allowMultiple)
    }

    @Test
    fun `image-only MIME types support image capture and visual picking`() {
        listOf(
            arrayOf("image/*"),
            arrayOf("image/jpeg"),
            arrayOf("image/jpeg", "image/png"),
        ).forEach { mimeTypes ->
            val request = request(*mimeTypes)

            assertTrue(request.acceptsImages)
            assertFalse(request.acceptsVideos)
            assertTrue(request.isVisualMedia)
            assertTrue(request.supportsOnlyImages)
            assertFalse(request.supportsOnlyVideos)
        }
    }

    @Test
    fun `video-only MIME types support video capture and visual picking`() {
        listOf(
            arrayOf("video/*"),
            arrayOf("video/mp4"),
            arrayOf("video/mp4", "video/webm"),
        ).forEach { mimeTypes ->
            val request = request(*mimeTypes)

            assertFalse(request.acceptsImages)
            assertTrue(request.acceptsVideos)
            assertTrue(request.isVisualMedia)
            assertFalse(request.supportsOnlyImages)
            assertTrue(request.supportsOnlyVideos)
        }
    }

    @Test
    fun `mixed image and video request is visual without supporting capture-only mode`() {
        val request = request("image/*", "video/*")

        assertTrue(request.acceptsImages)
        assertTrue(request.acceptsVideos)
        assertTrue(request.isVisualMedia)
        assertFalse(request.supportsOnlyImages)
        assertFalse(request.supportsOnlyVideos)
    }

    @Test
    fun `global wildcard accepts both visual media families`() {
        val request = request("*/*")

        assertTrue(request.acceptsImages)
        assertTrue(request.acceptsVideos)
        assertTrue(request.isVisualMedia)
        assertFalse(request.supportsOnlyImages)
        assertFalse(request.supportsOnlyVideos)
    }

    @Test
    fun `document-only MIME types do not claim visual media support`() {
        val request = request("application/pdf", "text/plain")

        assertFalse(request.acceptsImages)
        assertFalse(request.acceptsVideos)
        assertFalse(request.isVisualMedia)
        assertFalse(request.supportsOnlyImages)
        assertFalse(request.supportsOnlyVideos)
    }

    @Test
    fun `mixed visual and document MIME types keep relevant capture actions but use documents`() {
        val imageAndDocument = request("image/jpeg", "application/pdf")
        assertTrue(imageAndDocument.acceptsImages)
        assertFalse(imageAndDocument.acceptsVideos)
        assertFalse(imageAndDocument.isVisualMedia)
        assertFalse(imageAndDocument.supportsOnlyImages)
        assertFalse(imageAndDocument.supportsOnlyVideos)

        val videoAndDocument = request("video/mp4", "application/pdf")
        assertFalse(videoAndDocument.acceptsImages)
        assertTrue(videoAndDocument.acceptsVideos)
        assertFalse(videoAndDocument.isVisualMedia)
        assertFalse(videoAndDocument.supportsOnlyImages)
        assertFalse(videoAndDocument.supportsOnlyVideos)
    }

    private fun request(
        vararg mimeTypes: String,
        chooserMode: Int = WebChromeClient.FileChooserParams.MODE_OPEN,
    ): FileSelectionRequest = FileSelectionRequest.from(
        rawMimeTypes = mimeTypes.map { it }.toTypedArray(),
        chooserMode = chooserMode,
    )
}
