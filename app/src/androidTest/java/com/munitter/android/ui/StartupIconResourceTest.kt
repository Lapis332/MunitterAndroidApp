package com.munitter.android.ui

import android.graphics.Rect
import android.graphics.drawable.InsetDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.munitter.android.BuildConfig
import com.munitter.android.R
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupIconResourceTest {
    @Test
    fun developmentLauncherAndOsSplashKeepArtworkInsideTheSafeCircle() {
        assumeTrue(BuildConfig.ENVIRONMENT == "development")

        assertSixteenPercentInset(R.drawable.ic_launcher_foreground)
        assertSixteenPercentInset(R.drawable.ic_splash)
    }

    private fun assertSixteenPercentInset(resourceId: Int) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val inset = context.getDrawable(resourceId)

        assertTrue(inset is InsetDrawable)
        check(inset is InsetDrawable)
        inset.bounds = Rect(0, 0, TEST_BOUNDS_SIZE, TEST_BOUNDS_SIZE)

        val child = requireNotNull(inset.drawable)
        assertTrue(child.bounds.toString(), child.bounds.left in 159..160)
        assertTrue(child.bounds.toString(), child.bounds.top in 159..160)
        assertTrue(child.bounds.toString(), child.bounds.right in 840..841)
        assertTrue(child.bounds.toString(), child.bounds.bottom in 840..841)
    }

    private companion object {
        const val TEST_BOUNDS_SIZE = 1_000
    }
}
