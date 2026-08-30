package com.munitter.android.navigation

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.net.toUri
import com.munitter.android.R

class ExternalNavigator(
    private val activity: ComponentActivity,
    private val navigationPolicy: NavigationPolicy,
    private val loadInternalUrl: (String) -> Unit,
) {
    fun open(decision: NavigationDecision) {
        val rawUrl = decision.uri?.toString() ?: return
        when (decision.target) {
            NavigationTarget.EXTERNAL_BROWSER -> openHttps(rawUrl)
            NavigationTarget.SPECIAL_INTENT -> openSpecial(rawUrl)
            NavigationTarget.INTERNAL,
            NavigationTarget.ACCESS_IN_WEBVIEW,
            NavigationTarget.OAUTH_IN_WEBVIEW,
            -> loadInternalUrl(rawUrl)
            NavigationTarget.BLOCKED -> showBlocked()
        }
    }

    fun showBlocked() {
        Toast.makeText(activity, R.string.unsafe_link, Toast.LENGTH_SHORT).show()
    }

    private fun openHttps(rawUrl: String) {
        val intent = Intent(Intent.ACTION_VIEW, rawUrl.toUri()).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        launch(intent)
    }

    private fun openSpecial(rawUrl: String) {
        val uri = rawUrl.toUri()
        when (uri.scheme?.lowercase()) {
            "mailto" -> launch(Intent(Intent.ACTION_SENDTO, uri))
            "tel" -> launch(Intent(Intent.ACTION_DIAL, uri))
            "intent" -> openIntentUri(rawUrl)
            else -> showBlocked()
        }
    }

    private fun openIntentUri(rawUrl: String) {
        val parsed = runCatching {
            Intent.parseUri(rawUrl, Intent.URI_INTENT_SCHEME)
        }.getOrNull() ?: return showBlocked()

        val fallback = parsed.getStringExtra("browser_fallback_url")
        parsed.component = null
        parsed.selector = null
        parsed.addCategory(Intent.CATEGORY_BROWSABLE)
        parsed.flags = parsed.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION.inv() and
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION.inv()

        when (tryLaunch(parsed)) {
            LaunchResult.OPENED -> return
            LaunchResult.BLOCKED -> {
                showBlocked()
                return
            }
            LaunchResult.NO_HANDLER -> Unit
        }

        val fallbackUri = runCatching { java.net.URI(fallback.orEmpty()) }.getOrNull()
        if (
            fallbackUri != null &&
            fallbackUri.scheme.equals("https", ignoreCase = true) &&
            fallbackUri.userInfo == null
        ) {
            val decision = navigationPolicy.classify(fallbackUri.toString(), false)
            when (decision.target) {
                NavigationTarget.INTERNAL -> loadInternalUrl(fallbackUri.toString())
                NavigationTarget.EXTERNAL_BROWSER -> {
                    val host = fallbackUri.host.orEmpty().lowercase()
                    if (
                        host == "media.munitter.com" ||
                        host.endsWith(".r2.cloudflarestorage.com")
                    ) {
                        showBlocked()
                    } else {
                        openHttps(fallbackUri.toString())
                    }
                }
                else -> showBlocked()
            }
        } else {
            showNoHandler()
        }
    }

    private fun launch(intent: Intent) {
        when (tryLaunch(intent)) {
            LaunchResult.OPENED -> Unit
            LaunchResult.NO_HANDLER -> showNoHandler()
            LaunchResult.BLOCKED -> showBlocked()
        }
    }

    private fun tryLaunch(intent: Intent): LaunchResult =
        try {
            activity.startActivity(intent)
            LaunchResult.OPENED
        } catch (_: ActivityNotFoundException) {
            LaunchResult.NO_HANDLER
        } catch (_: SecurityException) {
            LaunchResult.BLOCKED
        }

    private fun showNoHandler() {
        Toast.makeText(activity, R.string.no_external_app, Toast.LENGTH_SHORT).show()
    }

    private enum class LaunchResult {
        OPENED,
        NO_HANDLER,
        BLOCKED,
    }
}
