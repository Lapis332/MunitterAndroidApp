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

internal enum class NotificationReadOutcome { Acknowledged, NotAcknowledged, Unauthorized, Retryable }

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
        var activeAccountUnreadCount: Int? = null
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
                    if (firstPage) activeAccountUnreadCount = page.unreadCount
                    items += page.items
                    seen += page.items.flatMap { it.sourceNotificationIds }
                    offset += page.items.size
                    firstPage = false
                    // DMs are omitted from this API. Their IDs must not force
                    // every foreground refresh to traverse notification history.
                    if (activeAccountUnreadCount == 0 || !page.hasMore || page.items.isEmpty() ||
                        sourcesBefore.all { it in seen || NotificationIdentity.isUnpersisted(it) }) break
                }
            }
        } while (true)

        currentCoroutineContext().ensureActive()
        if (!repository.isCurrentSession(session)) return@withContext superseded()
        // The notifications page total belongs to the selected account. Native
        // launcher state must use the existing linked-device aggregate instead.
        val authoritativeUnreadCount = when (val count = repository.fetchDeviceUnreadCount(session)) {
            NotificationCountResult.Unauthorized -> {
                if (!repository.isCurrentSession(session)) return@withContext superseded()
                if (!coordinator.clear(ticket, renderer::publish)) return@withContext superseded()
                return@withContext NotificationSyncOutcome.Unauthorized
            }
            NotificationCountResult.RetryableFailure -> return@withContext NotificationSyncOutcome.Retryable(
                IOException("Device unread reconciliation deferred"),
            )
            is NotificationCountResult.Count -> count.value
        }
        currentCoroutineContext().ensureActive()
        if (!repository.isCurrentSession(session)) return@withContext superseded()
        val mutation = coordinator.applyRefresh(ticket, items, authoritativeUnreadCount, renderer::publish)
            ?: return@withContext superseded()
        renderer.updateAvatars(mutation)
        NotificationSyncOutcome.Succeeded
    }

    internal suspend fun acknowledgeRead(id: String): NotificationReadOutcome = withContext(Dispatchers.IO) {
        val ticket = coordinator.beginSync()
        val session = repository.captureSession()
        val response = repository.acknowledgeRead(id, session)
        currentCoroutineContext().ensureActive()
        if (!repository.isCurrentSession(session)) return@withContext NotificationReadOutcome.Retryable
        when (response) {
            NotificationReadResult.Unauthorized -> NotificationReadOutcome.Unauthorized
            NotificationReadResult.RetryableFailure -> NotificationReadOutcome.Retryable
            is NotificationReadResult.Acknowledged -> {
                coordinator.applyReadAcknowledgment(ticket, response.value.acknowledgedIds,
                    response.value.unreadCount, renderer::publish) ?: return@withContext NotificationReadOutcome.Retryable
                if (response.value.acknowledgedIds.isEmpty()) NotificationReadOutcome.NotAcknowledged
                else NotificationReadOutcome.Acknowledged
            }
        }
    }

    private fun superseded(): NotificationSyncOutcome =
        NotificationSyncOutcome.Retryable(IOException("Notification state changed during refresh"))
}
