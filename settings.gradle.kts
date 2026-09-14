pluginManagement {
    repositories {
        maven { url = uri("https://dl-ssl.google.com/dl/android/maven2/") }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://dl-ssl.google.com/dl/android/maven2/") }
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "yt-dlp-Android"
include(":app")
