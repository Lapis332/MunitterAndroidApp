import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

// Development Release is intentionally unregistered. Development Debug and
// the explicitly registered Production variants use variant-scoped Firebase
// configuration files so environment credentials cannot cross boundaries.
tasks.matching { task ->
    task.name.startsWith("processDevelopmentRelease") && task.name.endsWith("GoogleServices")
}.configureEach {
    enabled = false
}

android {
    namespace = "com.munitter.android"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.munitter.android"
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

    flavorDimensions += "environment"
    productFlavors {
        create("development") {
            dimension = "environment"
            // Preserve the installed Development identity and its existing
            // Firebase registration while Production adopts the formal ID.
            applicationId = "com.munitter.android.provisional.development"
            versionNameSuffix = "-development"
            resValue("string", "app_name", "むにったー (開発)")
            buildConfigField("String", "ENVIRONMENT", "\"development\"")
            buildConfigField("String", "BASE_URL", "\"https://dev.munitter.com/\"")
            buildConfigField("String", "INTERNAL_HOST", "\"dev.munitter.com\"")
            buildConfigField("boolean", "WEBVIEW_DEBUGGABLE", "true")
            buildConfigField("boolean", "ENABLE_STARTUP_OVERLAY", "true")
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
            buildConfigField("String", "BASE_URL", "\"https://munitter.com/\"")
            buildConfigField("String", "INTERNAL_HOST", "\"munitter.com\"")
            buildConfigField("boolean", "WEBVIEW_DEBUGGABLE", "false")
            buildConfigField("boolean", "ENABLE_STARTUP_OVERLAY", "false")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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
