// Wispr by Deepu Gupta. Copyright 2026 Deepu Gupta. SPDX-License-Identifier: Apache-2.0
// AGP 9: Kotlin is built in, so there is no "org.jetbrains.kotlin.android" plugin and no kotlinOptions block.
plugins {
    id("com.android.application")
}

// Version: one place (gradle.properties "wispr.version"); a release tag like v4.2.0 overrides it.
val wisprVersion: String = System.getenv("WISPR_VERSION_NAME")?.takeIf { it.isNotBlank() }
    ?: providers.gradleProperty("wispr.version").orNull ?: "4.1.0"

// versionCode grows with the version: 4.1.0 -> 40100, 4.2.3 -> 40203 (minor/patch must stay below 100).
fun codeOf(v: String): Int {
    val p = v.substringBefore('-').substringBefore('+').split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)
    return p[0] * 10000 + p[1] * 100 + p[2]
}

// GitHub repo the in-app updater checks ("owner/name"). GitHub Actions passes github.repository automatically.
val wisprRepo: String = (System.getenv("WISPR_REPO")?.takeIf { it.isNotBlank() }
    ?: providers.gradleProperty("wispr.repo").orNull ?: "").trim().filter { it.isLetterOrDigit() || it in "/._-" }
val selfUpdate: Boolean = (System.getenv("WISPR_SELF_UPDATE") ?: providers.gradleProperty("wispr.selfUpdate").orNull ?: "true") != "false"

// Release signing comes from environment variables (GitHub Secrets). Never commit the keystore.
val ksFile: File? = System.getenv("WISPR_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.deepugupta.wispr"
    // Android 17 SDK (API 37). compileSdk 36 failed because the newest AndroidX libraries need 37.
    // minSdk stays 26 (Android 8.0), so the app still runs on Android 8.0 and newer.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.deepugupta.wispr"
        minSdk = 26
        targetSdk = 36
        versionCode = System.getenv("WISPR_VERSION_CODE")?.toIntOrNull() ?: codeOf(wisprVersion)
        versionName = wisprVersion
        buildConfigField("String", "OWNER", "\"Deepu Gupta\"")
        buildConfigField("String", "COPYRIGHT", "\"Copyright 2026 Deepu Gupta. Apache-2.0\"")
        buildConfigField("String", "UPDATE_REPO", "\"$wisprRepo\"")
        buildConfigField("boolean", "SELF_UPDATE", selfUpdate.toString())
    }

    signingConfigs {
        if (ksFile != null) {
            create("release") {
                storeFile = ksFile
                storePassword = System.getenv("WISPR_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("WISPR_KEY_ALIAS")
                keyPassword = System.getenv("WISPR_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (ksFile != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.17.1")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
}
