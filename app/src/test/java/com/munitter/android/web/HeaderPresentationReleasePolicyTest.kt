package com.munitter.android.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeaderPresentationReleasePolicyTest {
    @Test
    fun `same avatar remains covered until decoded ready state`() {
        assertFalse(HeaderPresentationReleasePolicy.shouldRelease("7", "7", "pending", timedOut = false))
        assertTrue(HeaderPresentationReleasePolicy.shouldRelease("7", "7", "ready", timedOut = false))
    }

    @Test
    fun `account switch releases old user immediately`() {
        assertTrue(HeaderPresentationReleasePolicy.shouldRelease("7", "8", "pending", timedOut = false))
    }

    @Test
    fun `formal fallback and failsafe cannot retain snapshot forever`() {
        assertFalse(HeaderPresentationReleasePolicy.shouldRelease("7", "7", "formal-fallback", timedOut = false))
        assertTrue(HeaderPresentationReleasePolicy.shouldRelease("7", "7", "pending", timedOut = true))
        assertTrue(HeaderPresentationReleasePolicy.shouldRelease("", "7", "formal-fallback", timedOut = false))
    }
}
