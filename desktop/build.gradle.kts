import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    // Versions live in the root build file; the Kotlin plugin is already on the
    // classpath from the Android modules and Gradle refuses a second declaration.
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
}

compose.desktop {
    application {
        mainClass = "com.lain.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Lain Desktop"
            packageVersion = "1.0.0"
        }
    }
}
