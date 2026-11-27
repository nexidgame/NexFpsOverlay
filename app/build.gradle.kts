import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.nex.fps"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nex.fps"
        minSdk = 30          // Android 11
        targetSdk = 36       // Android 16
        versionCode = 1
        versionName = "1.0.0"
    }

    // Tanda tangan rilis memakai keystore sendiri bila variabel lingkungan NEX_KEYSTORE_PATH diisi
    // (GitHub Actions mengisinya dari Secrets, lihat README). Bila tidak, dipakai kunci debug
    // supaya APK tetap bisa dipasang.
    signingConfigs {
        val ksPath = System.getenv("NEX_KEYSTORE_PATH")
        if (!ksPath.isNullOrEmpty() && file(ksPath).exists()) {
            create("nexRelease") {
                storeFile = file(ksPath)
                storePassword = System.getenv("NEX_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("NEX_KEY_ALIAS")
                keyPassword = System.getenv("NEX_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Dikecilkan dengan R8 (hapus kode tak terpakai) dan hapus resource tak terpakai.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("nexRelease") ?: signingConfigs.getByName("debug")
        }
    }

    // Hanya sertakan terjemahan bahasa Inggris dan Indonesia dari library (Compose/Material memuat puluhan bahasa).
    androidResources {
        localeFilters += listOf("en", "id")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        aidl = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui:1.9.4")
    implementation("androidx.compose.foundation:foundation:1.9.4")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Shizuku (akses shell tanpa root)
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
