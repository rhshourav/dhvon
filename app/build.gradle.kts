// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.rhshourav.dhvon"
    compileSdk = 37 // Compose 1.12 (BOM 2026.08) requires compileSdk 37

    defaultConfig {
        applicationId = "com.rhshourav.dhvon"
        minSdk = 26 // PiP + modern MediaStore; covers ~99% of active devices
        targetSdk = 36
        versionCode = 4
        versionName = "3.5.0"
        buildConfigField("String", "AUTHOR", "\"rhshourav\"")
        buildConfigField("String", "REPO_URL", "\"https://github.com/rhshourav/DHVON\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // One APK per CPU architecture, plus a universal APK that runs on any device.
    // libVLC and the FFmpeg decoder ship native libraries for every architecture, which is
    // what makes a single APK so large; each phone only needs its own.
    // Don't combine this with ndk { abiFilters }: the two conflict.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // libvlc-all and the Jellyfin FFmpeg decoder can both bundle libc++_shared.so — keep the first one.
        jniLibs { useLegacyPackaging = false; pickFirsts += "**/libc++_shared.so" }
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.palette)
    implementation(libs.kotlinx.coroutines.android)

    // Compose (Material 3)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Persistence
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Playback — keep media3 + ffmpeg decoder on the same release (see libs.versions.toml)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)
    implementation(libs.media3.ui.compose)
    implementation(libs.media3.ffmpeg.decoder)

    // Software video fallback (DivX / Xvid / MS-MPEG4 .avi, MPEG-2 .ts) when MediaCodec has no decoder — LGPL-2.1+.
    // If Gradle can't resolve 3.6.0, move to the newest 3.6.x on Maven Central.
    implementation("org.videolan.android:libvlc-all:3.6.0")

    // Images / thumbnails
    implementation(libs.coil.compose)
    implementation(libs.coil.video)

    // Drag-and-drop lists
    implementation(libs.reorderable)
}
