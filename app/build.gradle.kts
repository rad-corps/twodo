import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "app.twodo"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.twodo"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("twodo.versionCode").get().toInt()
        versionName = providers.gradleProperty("twodo.versionName").get()
    }

    // Release signing key lives outside the repo; see README. Without it, release builds are unsigned.
    val keystoreFile = file("${System.getProperty("user.home")}/.twodo/keystore.properties")
    val releaseSigning = if (keystoreFile.exists()) {
        val props = Properties().apply { keystoreFile.inputStream().use(::load) }
        signingConfigs.create("release") {
            // A relative storeFile (e.g. "release.jks") is next to keystore.properties, so ~/.twodo can be copied as-is.
            storeFile = keystoreFile.parentFile.resolve(props.getProperty("storeFile"))
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    } else {
        null
    }

    buildTypes {
        // Installs next to the released app ("TwoDo (dev)", own data), so any machine's debug key works.
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-dev"
        }
        release {
            isMinifyEnabled = false
            signingConfig = releaseSigning
            // Every phone from the last several years is 64-bit ARM; skipping other ABIs cuts the APK size.
            ndk { abiFilters += "arm64-v8a" }
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("io.getstream:stream-webrtc-android:1.3.10")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("sh.calvin.reorderable:reorderable:3.1.0")

    testImplementation("junit:junit:4.13.2")
}
