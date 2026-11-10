import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.tewnut.poicapture"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.tewnut.poicapture"
        minSdk = 30            // Android 11+: raw GNSS, camera/location foreground-service types
        targetSdk = 35
        versionCode = 14
        versionName = "0.13.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        // Release key from the environment (GitHub Actions secrets, see .github/workflows/release.yml). Without it the
        // release build is unsigned; debug builds always use the local debug key.
        val keystore = System.getenv("RELEASE_KEYSTORE_FILE")
        if (!keystore.isNullOrBlank()) create("release") {
            storeFile = file(keystore)
            storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")?.trim()
            keyAlias = System.getenv("RELEASE_KEY_ALIAS")?.trim()
            keyPassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")?.trim()
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

val camerax = "1.4.2"

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.core:core-splashscreen:1.0.1")   // splash screen on every Android version (logo on green)
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation(platform("androidx.compose:compose-bom:2024.11.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation(project(":shared"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // reads the signboards of places on the phone (Latin script incl. Vietnamese). Model from Google Play services,
    // downloaded at install (manifest meta-data): +0.3 MB instead of +41 MB of native libs for the bundled model. On a
    // phone without Play services the reading is skipped and the server reads the photos as before.
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20180813")   // real org.json for JVM tests (android.jar only has stubs)
}
