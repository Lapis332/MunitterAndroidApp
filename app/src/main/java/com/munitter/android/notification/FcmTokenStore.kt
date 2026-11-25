package com.munitter.android.notification

import android.content.Context
import com.google.firebase.FirebaseApp
import com.munitter.android.BuildConfig

internal class FcmTokenStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE,
    )

    private val cache = FcmTokenCache(
        storage = object : FcmTokenCacheStorage {
            override fun read() = FcmTokenCacheValues(
                preferences.getString(PROJECT_BINDING, null),
                preferences.getString(TOKEN, null),
                preferences.getString(REGISTERED_TOKEN, null),
            )

            override fun write(values: FcmTokenCacheValues) {
                // Preserve antiforgery, cookies, login and notification state.
                preferences.edit()
                    .putString(PROJECT_BINDING, values.binding)
                    .putString(TOKEN, values.token)
                    .putString(REGISTERED_TOKEN, values.registeredToken)
                    .apply()
            }
        },
        activeBinding = ::activeProjectBinding,
        lock = CACHE_LOCK,
        generation = CACHE_GENERATION,
    )

    fun captureBinding(): String? = cache.captureBinding()

    fun captureLookup(): FcmTokenLookup? = cache.captureLookup()

    fun publishLookup(token: String, lookup: FcmTokenLookup): Boolean = cache.publishLookup(token, lookup)

    fun save(token: String, expectedBinding: String? = captureBinding()): Boolean =
        cache.save(token, expectedBinding)

    fun read(): String? = cache.snapshot()?.token

    fun snapshot(): FcmTokenSnapshot? = cache.snapshot()

    fun isCurrent(snapshot: FcmTokenSnapshot): Boolean = cache.isCurrent(snapshot)

    fun markRegistered(snapshot: FcmTokenSnapshot): Boolean = cache.markRegistered(snapshot)

    fun clearRegistered(snapshot: FcmTokenSnapshot? = null) = cache.clearRegistered(snapshot)

    fun registeredToken(): String? = cache.registeredToken()

    private fun activeProjectBinding(): String? {
        if (BuildConfig.ENVIRONMENT == "production") {
            return FcmTokenProjectBinding.LEGACY_PRODUCTION
        }
        val options = runCatching { FirebaseApp.getInstance().options }.getOrNull() ?: return null
        return FcmTokenProjectBinding.create(
            BuildConfig.ENVIRONMENT,
            BuildConfig.FIREBASE_PROJECT_ID,
            options.projectId,
            options.applicationId,
            options.gcmSenderId,
            appContext.packageName,
        )
    }

    fun saveAntiForgeryToken(token: String) {
        preferences.edit().putString(ANTI_FORGERY_TOKEN, token.trim()).apply()
    }

    fun clearAntiForgeryToken() {
        preferences.edit().remove(ANTI_FORGERY_TOKEN).apply()
    }

    fun antiForgeryToken(): String? =
        preferences.getString(ANTI_FORGERY_TOKEN, null)?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        private const val PREFERENCES = "munitter_fcm"
        private const val TOKEN = "registration_token"
        private const val REGISTERED_TOKEN = "registered_token"
        private const val PROJECT_BINDING = "token_project_binding_v1"
        private const val ANTI_FORGERY_TOKEN = "anti_forgery_token"
        private val CACHE_LOCK = Any()
        private val CACHE_GENERATION = FcmTokenCacheGeneration()
    }
}
