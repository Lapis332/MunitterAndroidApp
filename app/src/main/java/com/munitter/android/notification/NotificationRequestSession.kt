package com.munitter.android.notification

// Deliberately not a data class: diagnostics must never stringify a Cookie.
internal class NotificationRequestSession internal constructor(internal val cookie: String?)

internal class NotificationSessionFence(private val readCookie: () -> String?) {
    fun capture(): NotificationRequestSession = NotificationRequestSession(currentCookie())

    fun isCurrent(session: NotificationRequestSession): Boolean = currentCookie() == session.cookie

    private fun currentCookie(): String? = readCookie()?.takeIf(String::isNotBlank)
}
