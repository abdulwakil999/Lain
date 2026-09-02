pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // Compose Multiplatform's plugin and desktop artifacts are published here.
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

rootProject.name = "Lain"
include(":app")

// Lain Desktop: a separate application that drives Nous Research's Hermes Agent.
// Its own module rather than a source set of :app — it shares no code with the
// phone, targets a different platform, and must not be able to break the APK.
include(":desktop")
