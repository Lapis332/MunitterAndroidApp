package com.munitter.android.notification

import android.content.Context
import android.util.Log
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

sealed interface NotificationSyncOutcome {
    data object Succeeded : NotificationSyncOutcome
    data object Unauthorized : NotificationSyncOutcome
    data class Retryable(val cause: Throwable) : NotificationSyncOutcome
}

class NotificationSyncEngine(context: Context) {
    private val appContext = context.applicationContext
    private val repository = MunitterNotificationRepository()
    private val coordinator = NotificationStateStore(appContext).coordinator
    private val renderer = NotificationRenderer(appContext, coordinator)

    suspend fun sync(): NotificationSyncOutcome = withContext(Dispatchers.IO) {
        val ticket = coordinator.beginSync()
        val session = repository.captureSession()
        val sourcesBefore = coordinator.ordinarySources()
        val seen = mutableSetOf<String>()
        val items = mutableListOf<MunitterNotification>()
        var offset = 0
        var firstPage = true
        var authoritativeUnreadCount: Int? = null
        do {
            val response = if (firstPage) repository.fetchRefresh(session) else repository.fetchMore(offset, session)
            currentCoroutineContext().ensureActive()
            if (!repository.isCurrentSession(session)) return@withContext superseded()
            when (response) {
                NotificationFetchResult.Unauthorized -> {
                    if (!coordinator.clear(ticket, renderer::publish)) return@withContext superseded()
                    return@withContext NotificationSyncOutcome.Unauthorized
                }
                is NotificationFetchResult.RetryableFailure -> {
                    Log.w(MunitterNotificationCenter.TAG, "Notification sync deferred", response.cause)
                    return@withContext NotificationSyncOutcome.Retryable(response.cause)
                }
                is NotificationFetchResult.Page -> {
                    val page = response.value
                    Log.d(
                        MunitterNotificationCenter.TAG,
                        "Notification sync page items=${page.items.size} unreadCount=${page.unreadCount} hasMore=${page.hasMore}",
                    )
                    if (firstPage) authoritativeUnreadCount = page.unreadCount
                    items += page.items
                    seen += page.items.flatMap { it.sourceNotificationIds }
                    offset += page.items.size
                    firstPage = false
                    // DMs are omitted from this API. Their IDs must not force
                    // every foreground refresh to traverse notification history.
                    if (authoritativeUnreadCount == 0 || !page.hasMore || page.items.isEmpty() ||
                        sourcesBefore.all { it in seen || NotificationIdentity.isUnpersisted(it) }) break
                }
            }
        } while (true)

        currentCoroutineContext().ensureActive()
        if (!repository.isCurrentSession(session)) return@withContext superseded()
        val mutation = coordinator.applyRefresh(ticket, items, authoritativeUnreadCount, renderer::publish)
            ?: return@withContext superseded()
        renderer.updateAvatars(mutation)
        NotificationSyncOutcome.Succeeded
    }

    private fun superseded(): NotificationSyncOutcome =
        NotificationSyncOutcome.Retryable(IOException("Notification state changed during refresh"))
}
