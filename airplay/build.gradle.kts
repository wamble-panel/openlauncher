// Built-in AirPlay receiver: the Kotlin side of jqssun/android-airplay-server
// (GPL-3.0, built on UxPlay). Its native libraries (UxPlay, FFmpeg, OpenSSL) are built
// on the "airplay" branch and downloaded into src/main/jniLibs by CI (see README.md).
plugins {
    id("com.android.library")
}

android {
    namespace = "io.github.jqssun.airplay"
    compileSdk {
        version = release(36) { minorApiLevel = 1 }
    }
    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.media3:media3-common:1.8.0")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
