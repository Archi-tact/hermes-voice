plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Relay base URLs, tried in order: USB (adb reverse) first, then the tailnet (tailscale serve).
// Set hermesRelayUrls in gradle.properties or with -PhermesRelayUrls=url1,url2.
val relayUrls = (findProperty("hermesRelayUrls") as String?) ?: "http://127.0.0.1:8765"

android {
    namespace = "com.architact.hermesvoice"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.architact.hermesvoice"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "RELAY_URLS", "\"$relayUrls\"")
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // android.jar only ships org.json stubs; the real implementation is needed on the JVM test classpath.
    testImplementation("org.json:json:20240303")
}
