package com.munitter.android.ui

import android.view.ViewGroup
import android.graphics.Bitmap
import android.webkit.WebView
import androidx.activity.BackEventCompat
import androidx.activity.ExperimentalActivityApi
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.viewinterop.AndroidView
import com.munitter.android.R
import com.munitter.android.web.WebUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

const val ERROR_PANEL_TEST_TAG = "web_error_panel"
const val LOADING_PANEL_TEST_TAG = "web_loading_panel"
const val NAVIGATION_HEADER_SNAPSHOT_TEST_TAG = "navigation_header_snapshot"
const val STARTUP_OVERLAY_TEST_TAG = "startup_overlay"
const val STARTUP_ICON_TEST_TAG = "startup_icon"
const val ENVIRONMENT_BADGE_TEST_TAG = "environment_badge"

@OptIn(ExperimentalActivityApi::class)
@Composable
fun MunitterScreen(
    webView: WebView?,
    state: WebUiState,
    navigationHeaderSnapshot: Bitmap? = null,
    startupOverlayVisible: Boolean = false,
    environmentBadge: String? = null,
    webViewDrawsBehindSystemBars: Boolean = false,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onEdgeNavigation: (swipeEdge: Int) -> Unit = { onBack() },
    onEdgeNavigationStart: suspend (swipeEdge: Int) -> Boolean = { false },
    onEdgeNavigationProgress: (swipeEdge: Int, progress: Float) -> Unit = { _, _ -> },
    onEdgeNavigationComplete: (swipeEdge: Int) -> Unit = {},
    onEdgeNavigationCancel: (swipeEdge: Int) -> Unit = {},
) {
    PredictiveBackHandler(enabled = true) { progress ->
        var gestureStartEdge: Int? = null
        var webOwned = false
        try {
            progress.collect { event ->
                // Latch ownership from the first platform sample. Even if a
                // future platform emitted an inconsistent later sample, one
                // gesture must never hand off to the opposite history owner.
                if (gestureStartEdge == null) {
                    gestureStartEdge = event.swipeEdge
                    webOwned = onEdgeNavigationStart(event.swipeEdge)
                }
                if (webOwned) {
                    onEdgeNavigationProgress(gestureStartEdge, event.progress)
                }
            }
            val completedSwipeEdge = gestureStartEdge ?: BackEventCompat.EDGE_NONE
            if (webOwned) {
                onEdgeNavigationComplete(completedSwipeEdge)
            } else if (completedSwipeEdge == BackEventCompat.EDGE_NONE) {
                onBack()
            } else {
                onEdgeNavigation(completedSwipeEdge)
            }
        } catch (_: CancellationException) {
            // A cancelled predictive gesture must not trigger navigation.
            gestureStartEdge?.let(onEdgeNavigationCancel)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val webContentModifier = if (webViewDrawsBehindSystemBars) {
            Modifier.fillMaxSize()
        } else {
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
        }

        Box(modifier = webContentModifier) {
            if (webView != null) {
                AndroidView(
                    factory = {
                        (webView.parent as? ViewGroup)?.removeView(webView)
                        webView
                    },
                    update = { view ->
                        val isInteractive =
                            state.failure == null && state.hasVisibleContent
                        view.alpha = if (isInteractive) 1f else 0f
                        view.isEnabled = isInteractive
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (navigationHeaderSnapshot != null && state.hasVisibleContent) {
                val snapshotHeight = with(LocalDensity.current) {
                    navigationHeaderSnapshot.height.toDp()
                }
                Image(
                    bitmap = navigationHeaderSnapshot.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(snapshotHeight)
                        .testTag(NAVIGATION_HEADER_SNAPSHOT_TEST_TAG),
                )
            }

            if (state.failure != null) {
                ErrorPanel(
                    state = state,
                    onRetry = onRetry,
                )
            } else if (!state.hasVisibleContent) {
                LoadingPanel(progress = state.progress)
            }
        }

        AnimatedVisibility(
            visible = startupOverlayVisible,
            modifier = Modifier.fillMaxSize(),
            enter = EnterTransition.None,
            exit = fadeOut(animationSpec = tween(durationMillis = STARTUP_FADE_DURATION_MS)),
        ) {
            StartupOverlay()
        }

        if (!environmentBadge.isNullOrBlank()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 6.dp, end = 8.dp)
                    .testTag(ENVIRONMENT_BADGE_TEST_TAG),
                color = Color(0xE6201B2A),
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, Color(0xFFFF4FA3)),
            ) {
                Text(
                    text = environmentBadge,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun StartupOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag(STARTUP_OVERLAY_TEST_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.munitter_app_icon_foreground),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(STARTUP_ICON_SIZE_DP.dp)
                .testTag(STARTUP_ICON_TEST_TAG),
        )
    }
}

@Composable
private fun LoadingPanel(progress: Int) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag(LOADING_PANEL_TEST_TAG),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            progress = { (progress.coerceIn(5, 100)) / 100f },
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.loading),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun ErrorPanel(
    state: WebUiState,
    onRetry: () -> Unit,
) {
    val failure = checkNotNull(state.failure)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 32.dp)
            .testTag(ERROR_PANEL_TEST_TAG),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(failure.titleRes),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(failure.messageRes),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRetry) {
            Text(text = stringResource(R.string.retry))
        }
    }
}

private const val STARTUP_FADE_DURATION_MS = 200
private const val STARTUP_ICON_SIZE_DP = 196
