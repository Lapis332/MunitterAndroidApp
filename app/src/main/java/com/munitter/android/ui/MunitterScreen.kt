package com.munitter.android.ui

import android.view.ViewGroup
import android.graphics.Bitmap
import android.webkit.WebView
import androidx.activity.ExperimentalActivityApi
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.munitter.android.R
import com.munitter.android.web.WebUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

const val ERROR_PANEL_TEST_TAG = "web_error_panel"
const val LOADING_PANEL_TEST_TAG = "web_loading_panel"
const val NAVIGATION_HEADER_SNAPSHOT_TEST_TAG = "navigation_header_snapshot"

@OptIn(ExperimentalActivityApi::class)
@Composable
fun MunitterScreen(
    webView: WebView?,
    state: WebUiState,
    navigationHeaderSnapshot: Bitmap? = null,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    PredictiveBackHandler(enabled = true) { progress ->
        try {
            progress.collect()
            onBack()
        } catch (_: CancellationException) {
            // A cancelled predictive gesture must not trigger navigation.
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
    ) {
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
            Image(
                bitmap = navigationHeaderSnapshot.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(56.dp)
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
