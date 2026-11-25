package com.munitter.android.notification

/** Public SDK identifiers bind a local cache; they are not server credential proof. */
internal object FcmTokenProjectBinding {
    const val LEGACY_PRODUCTION = "production:legacy"

    fun create(
        environment: String,
        compiledProjectId: String,
        sdkProjectId: String?,
        sdkApplicationId: String?,
        sdkSenderId: String?,
        packageName: String,
    ): String? {
        // Preserve the established Production cache and registration behavior.
        if (environment == "production") return LEGACY_PRODUCTION
        if (environment != "development" ||
            packageName !in setOf(
                "com.munitter.android.development",
                "com.munitter.android.development.debug",
            ) || sdkProjectId != compiledProjectId) return null

        val fields = listOf(
            environment, compiledProjectId, sdkProjectId, sdkApplicationId,
            sdkSenderId, packageName,
        )
        if (fields.any { value ->
                value.isNullOrBlank() || value.length > 256 ||
                    value != value.trim() || value.any(Char::isISOControl)
            }) return null

        // Length prefixes prevent identifiers containing separators from aliasing.
        return "fcm-project-cache/v1:" + fields.joinToString("") { "${it!!.length}:$it" }
    }
}
