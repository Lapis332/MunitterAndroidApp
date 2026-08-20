package com.munitter.android.notification

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

sealed interface NotificationSyncOutcome {
    data object Succeeded : NotificationSyncOutcome
    data object Unauthorized : NotificationSyncOutcome
    data class Retryable(val cause: Throwable) : NotificationSyncOutcome
}

class NotificationSyncEngine(context: Context) {
    private val appContext = context.applicationContext
    private val repository = MunitterNotificationRepository()
    private val center = MunitterNotificationCenter(appContext)
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    suspend fun sync(): NotificationSyncOutcome = withContext(Dispatchers.IO) {
        val activeBefore = readActiveIds()
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
                    for (item in page.items) {
                        seen += item.id
                        if (item.isRead) {
                            if (active.remove(item.id)) center.cancel(item.id)
                        } else {
                            val isNew = item.id !in activeBefore
                            active += item.id
                            center.show(item, active.size, alert = isNew)
                        }
                    }
                    hasMore = page.hasMore
                    offset += page.items.size
                    firstPage = false
                }
            }
        } while (hasMore && activeBefore.any { it !in seen })

        if (!hasMore || activeBefore.all { it in seen }) {
            (activeBefore - seen).forEach {
                active.remove(it)
                center.cancel(it)
            }
        }

        writeActiveIds(active)
        if (active.isEmpty()) {
            center.updateSummary(0)
        } else {
            center.updateSummary(active.size)
        }
        NotificationSyncOutcome.Succeeded
    }

    private fun clearStateAndNotifications() {
        preferences.edit().remove(ACTIVE_IDS).apply()
        center.cancelAll()
    }

    private fun readActiveIds(): Set<String> {
        val raw = preferences.getString(ACTIVE_IDS, null) ?: return emptySet()
        return runCatching {
            val array = JSONArray(raw)
            buildSet {
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.getOrDefault(emptySet())
    }

    private fun writeActiveIds(ids: Set<String>) {
        val array = JSONArray()
        ids.sorted().forEach(array::put)
        preferences.edit().putString(ACTIVE_IDS, array.toString()).apply()
    }

    private companion object {
        const val ACTIVE_IDS = "active_notification_ids"
        const val PREFERENCES = "munitter_notifications"
    }
}
