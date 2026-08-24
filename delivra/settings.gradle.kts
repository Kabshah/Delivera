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
        // JitPack — required for nodejs-mobile-android
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "Delivra"
include(":app")
