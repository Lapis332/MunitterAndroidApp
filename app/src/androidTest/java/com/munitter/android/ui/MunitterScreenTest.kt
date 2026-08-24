package com.munitter.android.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.munitter.android.web.WebFailureKind
import com.munitter.android.web.WebUiState
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MunitterScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun loadingStateShowsLoadingPanel() {
        composeRule.setContent {
            MunitterTheme {
                MunitterScreen(
                    webView = null,
                    state = WebUiState(
                        isLoading = true,
                        progress = 35,
                        hasVisibleContent = false,
                    ),
                    onRetry = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(LOADING_PANEL_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(ERROR_PANEL_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun coldLaunchShowsStartupOverlayAboveLoadingState() {
        composeRule.setContent {
            MunitterTheme {
                MunitterScreen(
                    webView = null,
                    state = WebUiState(isLoading = true),
                    startupOverlayVisible = true,
                    onRetry = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(STARTUP_OVERLAY_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun readyStateDoesNotKeepStartupOverlay() {
        composeRule.setContent {
            MunitterTheme {
                MunitterScreen(
                    webView = null,
                    state = WebUiState(hasVisibleContent = true),
                    startupOverlayVisible = false,
                    onRetry = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(STARTUP_OVERLAY_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(LOADING_PANEL_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun errorStateShowsErrorPanelAndRetryInvokesCallback() {
        val retryCount = AtomicInteger(0)
        composeRule.setContent {
            MunitterTheme {
                MunitterScreen(
                    webView = null,
                    state = WebUiState(
                        isLoading = false,
                        progress = 100,
                        hasVisibleContent = false,
                        failure = WebFailureKind.OFFLINE,
                    ),
                    onRetry = { retryCount.incrementAndGet() },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(ERROR_PANEL_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(LOADING_PANEL_TEST_TAG).assertDoesNotExist()
        composeRule.onNode(hasClickAction()).assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals(1, retryCount.get())
        }
    }

    @Test
    fun visibleContentKeepsCapturedHeaderAboveNavigationCommitGap() {
        val snapshot = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        composeRule.setContent {
            MunitterTheme {
                MunitterScreen(
                    webView = null,
                    state = WebUiState(hasVisibleContent = true),
                    navigationHeaderSnapshot = snapshot,
                    onRetry = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(NAVIGATION_HEADER_SNAPSHOT_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(LOADING_PANEL_TEST_TAG).assertDoesNotExist()
    }
}
