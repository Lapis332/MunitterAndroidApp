package com.munitter.android.notification

internal object NotificationUnreadCount {
    fun isAuthoritativelyEmpty(authoritativeCount: Int?): Boolean = authoritativeCount == 0

    // A push is not ordered current-state evidence. Zero requests an HTTP
    // refresh instead of overwriting a badge from a more recent notification.
    fun resolveForPush(authoritativeCount: Int?, activeNotificationCount: Int): Int? =
        if (isAuthoritativelyEmpty(authoritativeCount)) null
        else resolve(authoritativeCount, activeNotificationCount)

    fun resolve(authoritativeCount: Int?, activeNotificationCount: Int): Int =
        (authoritativeCount ?: activeNotificationCount).coerceAtLeast(0)
}
