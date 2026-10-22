pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    // PREFER_SETTINGS rather than FAIL: a user's init script may add mirror repositories to every project.
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "lyricsadapter-android"

include(":plugin-host")
include(":plugin-host-spike")
include(":spike-android")
