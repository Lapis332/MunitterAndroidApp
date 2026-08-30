package com.munitter.android.notification

internal object FcmRuntimeEnvironment {
    fun serverName(environment: String): String? = when (environment) {
        "development" -> "Development"
        "production" -> "Production"
        else -> null
    }

    fun isSupported(environment: String): Boolean = serverName(environment) != null
}
