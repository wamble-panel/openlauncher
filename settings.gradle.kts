pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // The Flutter screens (mmi_ui), built by `flutter build aar` into this local repo.
        maven { url = uri("mmi_ui/build/host/outputs/repo") }
        maven { url = uri("https://storage.googleapis.com/download.flutter.io") }
    }
}

rootProject.name = "OpenLauncher"
include(":app")
// Built-in AirPlay (iPhone screen mirroring and music); GPL-3.0, see airplay/README.md.
include(":airplay")
