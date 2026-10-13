plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.paparazzi)
}

// Per-developer overrides live in ~/.gradle/gradle.properties or -P flags, never in the repo.
fun prop(name: String, default: String) = (findProperty(name) as String?) ?: default

android {
    namespace = "app.thebridge"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.thebridge"
        minSdk = 29          // Android 10
        targetSdk = 35       // bump to the Play-required level at submission time
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "API_BASE", "\"https://api.thebridge.app\"")
        // OAuth *web* client id from Google Cloud (the same value goes in the server's GOOGLE_CLIENT_IDS). Empty = Google sign-in reports unavailable.
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${prop("bridge.googleWebClientId", "")}\"")
    }
    buildTypes {
        debug {
            // 10.0.2.2 is the host machine from the emulator; override with -Pbridge.apiBase=http://<lan-ip>:8080 for a phone
            buildConfigField("String", "API_BASE", "\"${prop("bridge.apiBase", "http://10.0.2.2:8080")}\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.preview)
    debugImplementation(libs.compose.tooling)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.process)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation)
    implementation(libs.hilt.work)
    ksp(libs.hilt.androidx.compiler)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.datastore)
    implementation(libs.credentials)
    implementation(libs.credentials.play)
    implementation(libs.googleid)
    implementation(libs.browser)
    implementation(libs.revenuecat)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}