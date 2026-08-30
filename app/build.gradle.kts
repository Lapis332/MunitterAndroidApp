import groovy.json.JsonSlurper
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

val developmentFirebaseProject = "munitter-dev-fcm-2026-db973d"
val productionFirebaseProject = "munitter-prod-fcm-2026-df60ow"
val developmentApplicationId = "com.munitter.android.development"
val productionApplicationId = "com.munitter.android"
val developmentBaseUrl = "https://dev.munitter.com/"
val productionBaseUrl = "https://munitter.com/"
val developmentInternalHost = "dev.munitter.com"
val productionInternalHost = "munitter.com"
val productionCloudflareAccessHost = "munitter.cloudflareaccess.com"
val productionCloudflareAccessCallbackHost = "www.munitter.com"

fun signingEnvironment(prefix: String): Map<String, String> = mapOf(
    "storeFile" to providers.environmentVariable("MUNITTER_ANDROID_${prefix}_KEYSTORE").orNull.orEmpty(),
    "storePassword" to providers.environmentVariable("MUNITTER_ANDROID_${prefix}_STORE_PASSWORD").orNull.orEmpty(),
    "keyAlias" to providers.environmentVariable("MUNITTER_ANDROID_${prefix}_KEY_ALIAS").orNull.orEmpty(),
    "keyPassword" to providers.environmentVariable("MUNITTER_ANDROID_${prefix}_KEY_PASSWORD").orNull.orEmpty(),
)

val developmentSigning = signingEnvironment("DEVELOPMENT")
val productionSigning = signingEnvironment("PRODUCTION")

android {
    namespace = "com.munitter.android"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = productionApplicationId
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        buildConfigField("String", "APP_UA_TOKEN", "\"MunitterAndroid/0.1.0\"")
        buildConfigField("String", "DEVELOPMENT_DEBUG_CLIENT_HEADER", "\"\"")
        buildConfigField("boolean", "ACCEPT_THIRD_PARTY_COOKIES", "false")
    }

    signingConfigs {
        create("development") {
            if (developmentSigning.values.all(String::isNotBlank)) {
                storeFile = file(developmentSigning.getValue("storeFile"))
                storePassword = developmentSigning.getValue("storePassword")
                keyAlias = developmentSigning.getValue("keyAlias")
                keyPassword = developmentSigning.getValue("keyPassword")
            }
        }
        create("production") {
            if (productionSigning.values.all(String::isNotBlank)) {
                storeFile = file(productionSigning.getValue("storeFile"))
                storePassword = productionSigning.getValue("storePassword")
                keyAlias = productionSigning.getValue("keyAlias")
                keyPassword = productionSigning.getValue("keyPassword")
            }
        }
    }

    flavorDimensions += "environment"
    productFlavors {
        create("development") {
            dimension = "environment"
            applicationId = developmentApplicationId
            versionNameSuffix = "-development"
            resValue("string", "app_name", "むにったー DEV")
            buildConfigField("String", "ENVIRONMENT", "\"development\"")
            buildConfigField("String", "BASE_URL", "\"$developmentBaseUrl\"")
            buildConfigField("String", "INTERNAL_HOST", "\"$developmentInternalHost\"")
            buildConfigField("String", "CLOUDFLARE_ACCESS_HOST", "\"\"")
            buildConfigField("String", "CLOUDFLARE_ACCESS_CALLBACK_HOST", "\"\"")
            buildConfigField("String", "FIREBASE_PROJECT_ID", "\"$developmentFirebaseProject\"")
            buildConfigField("String", "ENVIRONMENT_BADGE", "\"DEV\"")
            buildConfigField("boolean", "WEBVIEW_DEBUGGABLE", "true")
            buildConfigField("boolean", "ENABLE_STARTUP_OVERLAY", "true")
            manifestPlaceholders["appLinkHost"] = developmentInternalHost
            signingConfig = signingConfigs.getByName("development")
            buildConfigField(
                "String",
                "DEVELOPMENT_DEBUG_CLIENT_HEADER",
                "\"MunitterAndroid/0.1.0-development-debug\"",
            )
        }
        create("production") {
            dimension = "environment"
            resValue("string", "app_name", "むにったー")
            buildConfigField("String", "ENVIRONMENT", "\"production\"")
            buildConfigField("String", "BASE_URL", "\"$productionBaseUrl\"")
            buildConfigField("String", "INTERNAL_HOST", "\"$productionInternalHost\"")
            buildConfigField("String", "CLOUDFLARE_ACCESS_HOST", "\"$productionCloudflareAccessHost\"")
            buildConfigField(
                "String",
                "CLOUDFLARE_ACCESS_CALLBACK_HOST",
                "\"$productionCloudflareAccessCallbackHost\"",
            )
            buildConfigField("String", "FIREBASE_PROJECT_ID", "\"$productionFirebaseProject\"")
            buildConfigField("String", "ENVIRONMENT_BADGE", "\"\"")
            buildConfigField("boolean", "WEBVIEW_DEBUGGABLE", "false")
            buildConfigField("boolean", "ENABLE_STARTUP_OVERLAY", "false")
            manifestPlaceholders["appLinkHost"] = productionInternalHost
            signingConfig = signingConfigs.getByName("production")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("development")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

androidComponents {
    // Private and public Production builds share one signed Release identity.
    // A debug-signed Production package would be a second, unsafe signing
    // identity for the same environment, so that variant does not exist.
    beforeVariants(
        selector()
            .withFlavor("environment" to "production")
            .withBuildType("debug"),
    ) { variant ->
        variant.enable = false
    }
}

val verifyEnvironmentIsolation by tasks.registering {
    group = "verification"
    description = "Fails when an Android environment can consume another environment's identity or Firebase configuration."

    doLast {
        data class ExpectedFirebaseConfig(
            val path: String,
            val packageName: String,
            val projectId: String,
        )

        val expected = listOf(
            ExpectedFirebaseConfig(
                "src/developmentDebug/google-services.json",
                "$developmentApplicationId.debug",
                developmentFirebaseProject,
            ),
            ExpectedFirebaseConfig(
                "src/developmentRelease/google-services.json",
                developmentApplicationId,
                developmentFirebaseProject,
            ),
            ExpectedFirebaseConfig(
                "src/productionRelease/google-services.json",
                productionApplicationId,
                productionFirebaseProject,
            ),
        )

        val forbiddenFallbacks = listOf(
            file("src/main/google-services.json"),
            file("src/development/google-services.json"),
            file("src/production/google-services.json"),
            file("src/productionDebug/google-services.json"),
        )
        check(forbiddenFallbacks.none { it.exists() }) {
            "Environment-wide Firebase fallback configuration is forbidden; use an exact variant file."
        }

        expected.forEach { contract ->
            val configFile = file(contract.path)
            check(configFile.isFile) { "Missing exact Firebase configuration: ${contract.path}" }
            @Suppress("UNCHECKED_CAST")
            val root = JsonSlurper().parse(configFile) as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val projectInfo = root["project_info"] as? Map<String, Any?>
            check(projectInfo?.get("project_id") == contract.projectId) {
                "Firebase project mismatch for ${contract.path}"
            }
            @Suppress("UNCHECKED_CAST")
            val clients = root["client"] as? List<Map<String, Any?>> ?: emptyList()
            val packages = clients.mapNotNull { client ->
                @Suppress("UNCHECKED_CAST")
                val clientInfo = client["client_info"] as? Map<String, Any?>
                @Suppress("UNCHECKED_CAST")
                val androidInfo = clientInfo?.get("android_client_info") as? Map<String, Any?>
                androidInfo?.get("package_name") as? String
            }.toSet()
            check(contract.packageName in packages) {
                "Firebase package mismatch for ${contract.path}"
            }
            check(packages.none { packageName ->
                contract.projectId == developmentFirebaseProject &&
                    packageName == "com.munitter.android"
            }) { "Production package found in Development Firebase configuration" }
            check(packages.none { packageName ->
                contract.projectId == productionFirebaseProject &&
                    packageName.startsWith("com.munitter.android.development")
            }) { "Development package found in Production Firebase configuration" }
        }

        check(developmentFirebaseProject != productionFirebaseProject) {
            "Development and Production Firebase projects must remain distinct."
        }
        check(developmentApplicationId == "com.munitter.android.development")
        check(productionApplicationId == "com.munitter.android")
        check(developmentBaseUrl == "https://dev.munitter.com/")
        check(productionBaseUrl == "https://munitter.com/")
        check(developmentInternalHost == "dev.munitter.com")
        check(productionInternalHost == "munitter.com")
        check(productionCloudflareAccessHost == "munitter.cloudflareaccess.com")
        check(productionCloudflareAccessCallbackHost == "www.munitter.com")
        check(developmentApplicationId != productionApplicationId)
        check(developmentBaseUrl != productionBaseUrl)
        check(developmentInternalHost != productionInternalHost)
    }
}

val verifyDevelopmentSigning by tasks.registering {
    group = "verification"
    description = "Requires the dedicated Development signing identity."
    doLast {
        check(developmentSigning.values.all(String::isNotBlank)) {
            "Development signing material must be supplied by the protected build wrapper."
        }
        check(file(developmentSigning.getValue("storeFile")).isFile) {
            "Development signing keystore is unavailable."
        }
        check(developmentSigning.getValue("storeFile") != productionSigning["storeFile"]) {
            "Development and Production may not share a keystore."
        }
    }
}

val verifyProductionSigning by tasks.registering {
    group = "verification"
    description = "Requires the dedicated Production upload signing identity."
    doLast {
        check(productionSigning.values.all(String::isNotBlank)) {
            "Production signing material must be supplied by the protected build wrapper."
        }
        check(file(productionSigning.getValue("storeFile")).isFile) {
            "Production signing keystore is unavailable."
        }
        check(productionSigning.getValue("storeFile") != developmentSigning["storeFile"]) {
            "Production and Development may not share a keystore."
        }
    }
}

tasks.configureEach {
    when {
        name.matches(Regex("(?i).*(assemble|bundle|package|install)Development(Debug|Release).*")) ->
            dependsOn(verifyDevelopmentSigning)
        name.matches(Regex("(?i).*(assemble|bundle|package|install)ProductionRelease.*")) ->
            dependsOn(verifyProductionSigning)
    }
}

tasks.named("preBuild") {
    dependsOn(verifyEnvironmentIsolation)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.androidx.fragment)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit4)
    testImplementation("org.json:json:20240303")

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
