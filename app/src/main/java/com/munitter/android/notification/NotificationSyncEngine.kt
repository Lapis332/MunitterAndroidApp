package com.munitter.android.notification

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface NotificationSyncOutcome {
    data object Succeeded : NotificationSyncOutcome
    data object Unauthorized : NotificationSyncOutcome
    data class Retryable(val cause: Throwable) : NotificationSyncOutcome
}

class NotificationSyncEngine(context: Context) {
    private val appContext = context.applicationContext
    private val repository = MunitterNotificationRepository()
    private val center = MunitterNotificationCenter(appContext)
    private val stateStore = NotificationStateStore(appContext)
    private val avatarLoader = NotificationAvatarLoader(appContext)

    suspend fun sync(): NotificationSyncOutcome = withContext(Dispatchers.IO) {
        val activeBefore = stateStore.readActiveIds()
        val seen = mutableSetOf<String>()
        val active = activeBefore.toMutableSet()
        var offset = 0
        var hasMore = false
        var firstPage = true
        var response: NotificationFetchResult

        do {
            response = if (firstPage) repository.fetchRefresh() else repository.fetchMore(offset)
            when (response) {
                NotificationFetchResult.Unauthorized -> {
                    clearStateAndNotifications()
                    return@withContext NotificationSyncOutcome.Unauthorized
                }
                is NotificationFetchResult.RetryableFailure -> {
                    Log.w(MunitterNotificationCenter.TAG, "Notification sync deferred", response.cause)
                    return@withContext NotificationSyncOutcome.Retryable(response.cause)
                }
                is NotificationFetchResult.Page -> {
                    val page = response.value
                    if (firstPage && page.unreadCount == 0) {
                        // The notifications page does not contain DM rows, so
                        // an empty page alone cannot prove that a DM push was
                        // read. The server-provided aggregate is authoritative
                        // for clearing every native notification owned by this
                        // app instance.
                        clearStateAndNotifications()
                        return@withContext NotificationSyncOutcome.Succeeded
                    }
                    for (item in page.items) {
                        seen += item.id
                        if (item.isRead) {
                            if (active.remove(item.id)) center.cancel(item.id)
                        } else {
                            val isNew = item.id !in activeBefore
                            active += item.id
                            val avatarSpec = avatarLoader.specFor(
                                actorUserId = item.actorUserId,
                                relativeUrl = item.actorAvatarUrl,
                                version = item.actorAvatarVersion,
                            )
                            val cachedAvatar = avatarSpec?.let(avatarLoader::loadCached)
                            center.show(
                                item,
                                active.size,
                                alert = isNew,
                                actorAvatar = cachedAvatar,
                            )
                            if (avatarSpec != null && cachedAvatar == null) {
                                avatarLoader.loadOrFetch(avatarSpec)?.let { fetchedAvatar ->
                                    center.show(
                                        item,
                                        active.size,
                                        alert = false,
                                        actorAvatar = fetchedAvatar,
                                    )
                                }
                            }
                        }
                    }
                    hasMore = page.hasMore
                    offset += page.items.size
                    firstPage = false
                }
            }
        } while (hasMore && activeBefore.any { it !in seen })

        stateStore.writeActiveIds(active)
        if (active.isEmpty()) {
            center.updateSummary(0)
        } else {
            center.updateSummary(active.size)
        }
        NotificationSyncOutcome.Succeeded
    }

    private fun clearStateAndNotifications() {
        stateStore.clear()
        center.cancelAll()
    }
}
