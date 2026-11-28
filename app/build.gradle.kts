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
        applicationId = "vn.vmaps.quetpho"          // the store id; the code keeps its package (namespace)
        minSdk = 30            // Android 11+: raw GNSS, camera/location foreground-service types
        targetSdk = 36         // Play: new apps and updates must target the latest API level
        versionCode = 28
        versionName = "0.22.3"
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
        // debug installs next to the release app (own data), so testing never means uninstalling the real sessions
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }   // phones and the emulator (ONNX Runtime is ~18 MB per ABI)
        }
        release {
            isMinifyEnabled = false
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
                debugSymbolLevel = "SYMBOL_TABLE"   // native symbols in the .aab: readable native crashes in Play
            }
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
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
    // "Scan setup QR" (upload server settings): Google's code scanner runs its own camera screen in Play services, so
    // the app asks for no camera permission and ships no scanner UI or model (module downloaded at install, manifest).
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // indoor floor plans (docs/indoor.md §7): Google's document scanner finds, straightens and crops the mall's board
    // in its own screen (Play services module, downloaded on first use; minSdk 21). Without it: the app's corner editor.
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")
    // traffic-signs ride: the sign detector (assets/signs, ml/signs) on the CPU. MIT licence; native libs, so release
    // builds keep only the ARM ABIs real phones use; debug keeps arm64 and x86_64 (the emulator).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    // Camera 360 ride: the sync QR code shown to the 360° camera (docs/camera360.md §2). Encoder only, pure Java, ~0.5 MB
    implementation("com.google.zxing:core:3.5.3")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20180813")   // real org.json for JVM tests (android.jar only has stubs)
}
