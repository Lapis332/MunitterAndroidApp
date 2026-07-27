package com.munitter.android.media

import android.app.AlertDialog
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.munitter.android.R
import java.io.File
import java.util.Locale
import java.util.UUID

class FileChooserCoordinator(
    private val activity: ComponentActivity,
) {
    private var pendingCallback: ValueCallback<Array<Uri>>? = null
    private var captureUri: Uri? = null

    private val pickSingleMedia = activity.registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        deliver(uri?.let { arrayOf(it) })
    }

    private val pickMultipleMedia = activity.registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = MAX_MEDIA_ITEMS),
    ) { uris ->
        deliver(uris.takeIf { it.isNotEmpty() }?.toTypedArray())
    }

    private val openSingleDocument = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        deliver(uri?.let { arrayOf(it) })
    }

    private val openMultipleDocuments = activity.registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        deliver(uris.takeIf { it.isNotEmpty() }?.toTypedArray())
    }

    private val takePhoto = activity.registerForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { succeeded ->
        deliver(captureUri?.takeIf { succeeded }?.let { arrayOf(it) })
        captureUri = null
    }

    private val captureVideo = activity.registerForActivityResult(
        ActivityResultContracts.CaptureVideo(),
    ) { succeeded ->
        deliver(captureUri?.takeIf { succeeded }?.let { arrayOf(it) })
        captureUri = null
    }

    fun showFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean {
        cancelPending()
        pendingCallback = callback

        val request = FileSelectionRequest.from(params.acceptTypes, params.mode)
        if (params.isCaptureEnabled && request.supportsOnlyImages) {
            launchPhotoCapture()
            return true
        }
        if (params.isCaptureEnabled && request.supportsOnlyVideos) {
            launchVideoCapture()
            return true
        }

        val actions = buildList {
            if (request.isVisualMedia) {
                add(
                    ChooserAction(
                        activity.getString(R.string.choose_media),
                    ) { launchMediaPicker(request) },
                )
            }
            if (request.acceptsImages) {
                add(
                    ChooserAction(
                        activity.getString(R.string.take_photo),
                        ::launchPhotoCapture,
                    ),
                )
            }
            if (request.acceptsVideos) {
                add(
                    ChooserAction(
                        activity.getString(R.string.record_video),
                        ::launchVideoCapture,
                    ),
                )
            }
            add(
                ChooserAction(
                    activity.getString(R.string.choose_files),
                ) { launchDocumentPicker(request) },
            )
        }

        AlertDialog.Builder(activity)
            .setTitle(R.string.file_chooser_title)
            .setItems(actions.map { it.label }.toTypedArray()) { _, index ->
                actions[index].launch()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> deliver(null) }
            .setOnCancelListener { deliver(null) }
            .show()

        return true
    }

    fun cancelPending() {
        pendingCallback?.onReceiveValue(null)
        pendingCallback = null
        captureUri = null
    }

    private fun launchMediaPicker(request: FileSelectionRequest) {
        val mediaType = request.toPickerMediaType()
        val pickerRequest = PickVisualMediaRequest(mediaType)
        if (request.allowMultiple) {
            pickMultipleMedia.launch(pickerRequest)
        } else {
            pickSingleMedia.launch(pickerRequest)
        }
    }

    private fun launchDocumentPicker(request: FileSelectionRequest) {
        val types = request.mimeTypes.ifEmpty { listOf("*/*") }.toTypedArray()
        if (request.allowMultiple) {
            openMultipleDocuments.launch(types)
        } else {
            openSingleDocument.launch(types)
        }
    }

    private fun launchPhotoCapture() {
        runCatching {
            captureUri = createCaptureUri(extension = "jpg")
            takePhoto.launch(checkNotNull(captureUri))
        }.onFailure {
            captureUri = null
            Toast.makeText(activity, R.string.no_external_app, Toast.LENGTH_SHORT).show()
            deliver(null)
        }
    }

    private fun launchVideoCapture() {
        runCatching {
            captureUri = createCaptureUri(extension = "mp4")
            captureVideo.launch(checkNotNull(captureUri))
        }.onFailure {
            captureUri = null
            Toast.makeText(activity, R.string.no_external_app, Toast.LENGTH_SHORT).show()
            deliver(null)
        }
    }

    private fun createCaptureUri(extension: String): Uri {
        val directory = File(activity.cacheDir, "captured-media").apply { mkdirs() }
        val file = File(directory, "${UUID.randomUUID()}.$extension")
        return FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            file,
        )
    }

    private fun deliver(value: Array<Uri>?) {
        val callback = pendingCallback ?: return
        pendingCallback = null
        callback.onReceiveValue(value)
    }

    private data class ChooserAction(
        val label: String,
        val launch: () -> Unit,
    )

    companion object {
        private const val MAX_MEDIA_ITEMS = 50
    }
}

data class FileSelectionRequest(
    val mimeTypes: List<String>,
    val allowMultiple: Boolean,
) {
    val acceptsImages: Boolean =
        mimeTypes.isEmpty() || mimeTypes.any { it == "*/*" || it.startsWith("image/") }
    val acceptsVideos: Boolean =
        mimeTypes.isEmpty() || mimeTypes.any { it == "*/*" || it.startsWith("video/") }
    val isVisualMedia: Boolean =
        mimeTypes.isEmpty() ||
            mimeTypes.all {
                it == "*/*" || it.startsWith("image/") || it.startsWith("video/")
            }
    val supportsOnlyImages: Boolean =
        mimeTypes.isNotEmpty() && mimeTypes.all { it.startsWith("image/") }
    val supportsOnlyVideos: Boolean =
        mimeTypes.isNotEmpty() && mimeTypes.all { it.startsWith("video/") }

    fun toPickerMediaType(): ActivityResultContracts.PickVisualMedia.VisualMediaType {
        if (supportsOnlyImages) {
            val exact = mimeTypes.singleOrNull()?.takeUnless { it.endsWith("/*") }
            return exact?.let(ActivityResultContracts.PickVisualMedia::SingleMimeType)
                ?: ActivityResultContracts.PickVisualMedia.ImageOnly
        }
        if (supportsOnlyVideos) {
            val exact = mimeTypes.singleOrNull()?.takeUnless { it.endsWith("/*") }
            return exact?.let(ActivityResultContracts.PickVisualMedia::SingleMimeType)
                ?: ActivityResultContracts.PickVisualMedia.VideoOnly
        }
        return ActivityResultContracts.PickVisualMedia.ImageAndVideo
    }

    companion object {
        fun from(
            rawMimeTypes: Array<String>?,
            chooserMode: Int,
        ): FileSelectionRequest {
            val normalized = rawMimeTypes
                .orEmpty()
                .asSequence()
                .flatMap { it.split(',').asSequence() }
                .map { it.trim().lowercase(Locale.US) }
                .filter { it.isNotBlank() }
                .distinct()
                .toList()

            return FileSelectionRequest(
                mimeTypes = normalized,
                allowMultiple = chooserMode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE,
            )
        }
    }
}
