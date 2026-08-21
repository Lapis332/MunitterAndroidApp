package com.munitter.android.notification

import android.content.Context
import org.json.JSONArray

class NotificationStateStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    fun readActiveIds(): Set<String> {
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

    @Synchronized
    fun writeActiveIds(ids: Set<String>) {
        val array = JSONArray()
        ids.sorted().forEach(array::put)
        preferences.edit().putString(ACTIVE_IDS, array.toString()).apply()
    }

    fun addActiveId(id: String): Set<String> {
        val next = readActiveIds().toMutableSet().apply { add(id) }
        writeActiveIds(next)
        return next
    }

    fun clear() {
        preferences.edit().remove(ACTIVE_IDS).apply()
    }

    companion object {
        private const val PREFERENCES = "munitter_notifications"
        private const val ACTIVE_IDS = "active_notification_ids"
    }
}
