package com.munitter.android.notification

import android.content.Context

class FcmTokenStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE,
    )

    fun save(token: String) {
        preferences.edit().putString(TOKEN, token.trim()).apply()
    }

    fun read(): String? = preferences.getString(TOKEN, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun markRegistered(token: String) {
        preferences.edit().putString(REGISTERED_TOKEN, token.trim()).apply()
    }

    fun registeredToken(): String? =
        preferences.getString(REGISTERED_TOKEN, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun saveAntiForgeryToken(token: String) {
        preferences.edit().putString(ANTI_FORGERY_TOKEN, token.trim()).apply()
    }

    fun antiForgeryToken(): String? =
        preferences.getString(ANTI_FORGERY_TOKEN, null)?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        private const val PREFERENCES = "munitter_fcm"
        private const val TOKEN = "registration_token"
        private const val REGISTERED_TOKEN = "registered_token"
        private const val ANTI_FORGERY_TOKEN = "anti_forgery_token"
    }
}
