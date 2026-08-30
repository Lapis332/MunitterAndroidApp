package com.munitter.android.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveMediaSessionPolicyTest {
    @Test
    fun `cold process identifier clears only session reveal state`() {
        val first = SensitiveMediaSessionPolicy.documentStartScript(
            "11111111111111111111111111111111",
        )
        val coldLaunch = SensitiveMediaSessionPolicy.documentStartScript(
            "22222222222222222222222222222222",
        )

        assertNotEquals(first, coldLaunch)
        assertTrue(first.contains("window.sessionStorage.removeItem(revealKey)"))
        assertTrue(first.contains(SensitiveMediaSessionPolicy.REVEAL_STORAGE_KEY))
        assertTrue(first.contains(SensitiveMediaSessionPolicy.NATIVE_SESSION_STORAGE_KEY))
        assertTrue(first.contains(SensitiveMediaSessionPolicy.CLIENT_SESSION_WINDOW_NAME_PREFIX))
        assertTrue(first.contains("window.name = ''"))
        assertTrue(first.contains("let isCurrentProcess = false"))
        assertTrue(first.contains("let remainingAttempts = 200"))
        assertTrue(first.contains("window.setTimeout(invokeWebReset, 50)"))
        assertTrue(first.contains("hook.call(window.MunitterApp)"))
        assertTrue(SensitiveMediaSessionPolicy.SERVER_SESSION_BINDING_COOKIE.startsWith("munitter."))
        assertTrue(first.contains("resetSensitiveMediaRevealSession"))
        assertFalse(first.contains("localStorage"))
        assertFalse(first.contains("SharedPreferences"))
        assertFalse(first.contains("document.cookie"))
    }

    @Test
    fun `same process marker returns without clearing grants`() {
        val script = SensitiveMediaSessionPolicy.documentStartScript(
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        )

        assertTrue(script.contains(
            "isCurrentProcess = window.sessionStorage.getItem(markerKey) === processSessionId",
        ))
        assertTrue(script.contains("if (isCurrentProcess) return"))
        assertTrue(
            script.indexOf("if (isCurrentProcess) return") <
                script.indexOf("removeItem(revealKey)"),
        )
    }

    @Test
    fun `cookie matcher selects only the exact sensitive binding name`() {
        val header = "theme=dark; munitter.sensitive-media.session=protected; auth=session"

        assertTrue(SensitiveMediaCookiePolicy.containsCookie(
            header,
            SensitiveMediaSessionPolicy.SERVER_SESSION_BINDING_COOKIE,
        ))
        assertFalse(SensitiveMediaCookiePolicy.containsCookie(
            header,
            "sensitive-media.session",
        ))
        assertFalse(SensitiveMediaCookiePolicy.containsCookie(null, "auth"))
    }

    @Test
    fun `failed first cold reset drops saved state and every launch query`() {
        val plan = SensitiveMediaColdLaunchPolicy.plan(
            isFirstProcessLaunch = true,
            cookieResetSucceeded = false,
            requestedUrl = "https://dev.munitter.com/article/media/b/42?sensitiveReveal=old&sensitiveSession=old",
            safeFallbackUrl = "https://dev.munitter.com/home?sensitiveReveal=old#viewer",
        )

        assertFalse(plan.restoreSavedWebViewState)
        assertTrue(plan.requestedUrl == "https://dev.munitter.com/home")
    }

    @Test
    fun `successful cold reset may restore state because old capabilities lost their binding`() {
        val requested = "https://dev.munitter.com/post/42?sensitiveReveal=old"
        val plan = SensitiveMediaColdLaunchPolicy.plan(
            isFirstProcessLaunch = true,
            cookieResetSucceeded = true,
            requestedUrl = requested,
            safeFallbackUrl = "https://dev.munitter.com/home",
        )

        assertTrue(plan.restoreSavedWebViewState)
        assertTrue(plan.requestedUrl == requested)
    }

    @Test
    fun `same process recreation preserves current session after fail closed launch`() {
        val requested = "https://dev.munitter.com/dm/9"
        val plan = SensitiveMediaColdLaunchPolicy.plan(
            isFirstProcessLaunch = false,
            cookieResetSucceeded = false,
            requestedUrl = requested,
            safeFallbackUrl = "https://dev.munitter.com/home",
        )

        assertTrue(plan.restoreSavedWebViewState)
        assertTrue(plan.requestedUrl == requested)
    }

    @Test
    fun `invalid fallback stays blank rather than restoring a reveal URL`() {
        val plan = SensitiveMediaColdLaunchPolicy.plan(
            isFirstProcessLaunch = true,
            cookieResetSucceeded = false,
            requestedUrl = "https://dev.munitter.com/dm/media/9?sensitiveReveal=old",
            safeFallbackUrl = "javascript:alert(1)",
        )

        assertFalse(plan.restoreSavedWebViewState)
        assertNull(plan.requestedUrl)
    }

    @Test
    fun `process reset coordinator starts once and joins pending activities`() {
        val coordinator = SensitiveMediaColdLaunchResetCoordinator()
        var starts = 0
        var resetCompletion: ((Boolean) -> Unit)? = null
        val results = mutableListOf<Boolean>()
        val starter: ((Boolean) -> Unit) -> Unit = { completion ->
            starts += 1
            resetCompletion = completion
        }

        coordinator.prepare(starter) { results += it }
        coordinator.prepare(starter) { results += it }
        assertTrue(starts == 1)
        assertTrue(results.isEmpty())

        resetCompletion?.invoke(false)
        assertTrue(results == listOf(false, false))

        coordinator.prepare(starter) { results += it }
        assertTrue(starts == 1)
        assertTrue(results == listOf(false, false, false))
    }
}
