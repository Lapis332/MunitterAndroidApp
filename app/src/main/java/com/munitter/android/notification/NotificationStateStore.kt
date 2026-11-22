package com.munitter.android.notification

import android.content.Context

internal class NotificationStateStore(context: Context) {
    // These stores are created independently by Activity, WorkManager and FCM.
    // Their state transitions and native rendering need the same process lock.
    val coordinator: NotificationStateCoordinator = synchronized(Companion) {
        sharedCoordinator ?: context.applicationContext.getSharedPreferences(
            PREFERENCES,
            Context.MODE_PRIVATE,
        ).let { preferences ->
            NotificationStateCoordinator(
                read = {
                    NotificationStateCodec.decode(
                        preferences.getString(STATE, null),
                        preferences.getString(ACTIVE_IDS, null),
                    )
                },
                write = {
                    preferences.edit().putString(STATE, NotificationStateCodec.encode(it))
                        .remove(ACTIVE_IDS).apply()
                },
            ).also { sharedCoordinator = it }
        }
    }

    companion object {
        private const val PREFERENCES = "munitter_notifications"
        private const val ACTIVE_IDS = "active_notification_ids"
        private const val STATE = "active_notification_state_v2"
        private var sharedCoordinator: NotificationStateCoordinator? = null
    }
}
