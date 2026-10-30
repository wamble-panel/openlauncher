plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace  = "com.openlauncher.app"
    compileSdk {
        version = release(36) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId  = "com.openlauncher.app"
        minSdk         = 21
        targetSdk      = 36
        // CI builds count up with the run number, so Settings → Software update can compare.
        versionCode    = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()?.plus(1000) ?: 21
        versionName    = "2.0.0-mmi"
    }

    // CI points MMI_KEYSTORE at the owner's shared key, so every build installs
    // over the last one. Without it, the machine's own debug key is used.
    signingConfigs {
        getByName("debug") {
            System.getenv("MMI_KEYSTORE")?.takeIf { File(it).isFile }?.let {
                storeFile = File(it)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            // Default signing config for normal device testing (restores app visibility)
        }
        release {
            // R8: strips unused code/resources and optimises what is left —
            // smaller APK and faster start on the 2 GB head unit.
            isMinifyEnabled = true
            isShrinkResources = true
            // Sideload builds: sign with the debug key so the optimised (non-debuggable)
            // APK installs without a separate release keystore. Compose runs far
            // faster when not debuggable, which matters on low-end head units.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = false
        // Interfaces of the TS10 car apps' exported services (src/main/aidl).
        aidl = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)

    // Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")

    // Installs the bundled startup profile so ART pre-compiles the hot code
    // instead of interpreting it for the first days of use.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    // Offline vector maps (MMI navigation)
    implementation("org.maplibre.gl:android-sdk:11.5.2")

    // Reverse view: on-device object detection (bundled model, works offline).
    implementation("com.google.mlkit:object-detection:17.0.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Unit tests — pure-logic functions only (src/test runs on the local JVM)
    testImplementation("junit:junit:4.13.2")
}
