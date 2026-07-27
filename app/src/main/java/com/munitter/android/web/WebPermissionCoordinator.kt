package com.munitter.android.web

import android.Manifest
import android.content.pm.PackageManager
import android.webkit.PermissionRequest
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.munitter.android.navigation.NavigationPolicy

class WebPermissionCoordinator(
    private val activity: ComponentActivity,
    private val navigationPolicy: NavigationPolicy,
) {
    private var pendingRequest: PermissionRequest? = null

    private val microphonePermission = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val request = pendingRequest
        pendingRequest = null
        if (request == null) return@registerForActivityResult

        if (granted) {
            request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        } else {
            request.deny()
        }
    }

    fun onPermissionRequest(request: PermissionRequest) {
        activity.runOnUiThread {
            pendingRequest?.deny()
            pendingRequest = null

            val isTrusted = navigationPolicy.isTrustedOrigin(request.origin?.toString())
            val requestsAudio =
                request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)

            if (!isTrusted || !requestsAudio) {
                request.deny()
                return@runOnUiThread
            }

            if (
                ContextCompat.checkSelfPermission(
                    activity,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            } else {
                pendingRequest = request
                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    fun onPermissionRequestCanceled(request: PermissionRequest) {
        if (pendingRequest === request) {
            pendingRequest = null
        }
    }

    fun cancelPending() {
        pendingRequest?.deny()
        pendingRequest = null
    }
}
