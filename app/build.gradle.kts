import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.lain.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lain.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 44
        versionName = "1.17.3"
    }

    /**
     * Release signing, when the developer has supplied a keystore.
     *
     * Read from `keystore.properties` (git-ignored) or the matching environment
     * variables, so nothing secret lives in the repository. With neither present the
     * release build still produces an unsigned APK rather than failing — useful for
     * checking size and shrinking without holding the signing key.
     */
    val keystoreProperties = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    fun secret(key: String, env: String): String? =
        keystoreProperties.getProperty(key) ?: System.getenv(env)

    val releaseStore = secret("storeFile", "LAIN_KEYSTORE")?.let { file(it) }

    signingConfigs {
        if (releaseStore != null && releaseStore.exists()) {
            create("release") {
                storeFile = releaseStore
                storePassword = secret("storePassword", "LAIN_KEYSTORE_PASSWORD")
                keyAlias = secret("keyAlias", "LAIN_KEY_ALIAS")
                keyPassword = secret("keyPassword", "LAIN_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 on: a smaller dex is less to verify, load and keep resident, which
            // shows up as faster cold start and lower memory on the cheap phones this
            // is meant to run well on. Keep rules live in proguard-rules.pro; the
            // framework entry points named there cannot be inferred by R8 because the
            // manifest references them by string.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            // Distinct id so a debug build sits alongside a release one instead of
            // forcing an uninstall to switch between them.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    testOptions {
        unitTests {
            // AccessibilityMonitor logs through android.util.Log; without this the
            // JVM stubs throw "not mocked" and the state machine can't be tested at all.
            isReturnDefaultValues = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // Lets the app switch performance tracing on for debug builds only.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    // Photos carry their rotation in EXIF rather than in the pixels; without this a
    // portrait photo reaches the model sideways and it describes a sideways scene.
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Conversations and long-term memory need real querying and migrations,
    // which JSON-in-preferences can't give us.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    testImplementation("junit:junit:4.13.2")
    // Drives the streaming path against a real socket so time-to-first-token can be
    // measured rather than asserted.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
