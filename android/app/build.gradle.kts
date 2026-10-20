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

// Firebase (push) reads android/app/google-services.json, which is gitignored. Without it the app builds and push is off.
if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")

android {
    namespace = "app.thebridge"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.thebridge"
        minSdk = 29          // Android 10
        targetSdk = 35       // bump to the Play-required level at submission time
        versionCode = 1
        versionName = "1.0.0"
        // The ABIs the Rust crypto library is built for (JNA also ships older ones we don't need).
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        buildConfigField("String", "API_BASE", "\"https://api.thebridge.app\"")
        // OAuth *web* client id from Google Cloud (the same value goes in the server's GOOGLE_CLIENT_IDS). Empty = Google sign-in reports unavailable.
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${prop("bridge.googleWebClientId", "846759270213-n831n3thdu07p042vk1cn4q846t22eca.apps.googleusercontent.com")}\"")
        // RevenueCat public SDK key (goog_…). Empty = the paywall shows, but purchases are switched off.
        // PostHog project key (phc_…). Empty = no analytics.
        buildConfigField("String", "POSTHOG_KEY", "\"${prop("bridge.posthogKey", "")}\"")
        buildConfigField("String", "REVENUECAT_KEY", "\"${prop("bridge.revenuecatKey", "")}\"")
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
    // Olm for encrypted DMs: libbridge_crypto.so, built from ../crypto by the task below.
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("rustJniLibs"))
}

// Builds the Rust crypto library (vodozemac) for each Android ABI when the crate changes. Needs rustup with the
// Android targets, cargo-ndk and the NDK (see crypto/build-android.sh). Kotlin bindings are generated into
// src/main/java/uniffi and committed.
val buildCrypto by tasks.registering(Exec::class) {
    val crate = rootProject.file("../crypto")
    inputs.dir(crate.resolve("src")); inputs.file(crate.resolve("Cargo.toml")); inputs.file(crate.resolve("Cargo.lock"))
    outputs.dir(layout.buildDirectory.dir("rustJniLibs"))
    val ndk = android.sdkDirectory.resolve("ndk").listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }
    if (ndk != null) environment("ANDROID_NDK_HOME", ndk.absolutePath)
    // On Windows use Git Bash (a plain "bash" may be WSL, which can't see this toolchain).
    val gitBash = File("C:/Program Files/Git/bin/bash.exe")
    workingDir = crate
    commandLine(if (gitBash.exists()) gitBash.absolutePath else "bash", "build-android.sh", "../android/app/build/rustJniLibs")
}
tasks.named("preBuild") { dependsOn(buildCrypto) }

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.install.referrer)
    implementation(libs.coroutines.play.services)
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
    implementation("${libs.jna.get()}@aar")              // UniFFI bindings call the Rust library through JNA
    testImplementation(libs.jna)                            // desktop JNA for the bindings test against a host build of crypto/
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
    implementation(libs.revenuecat)
    implementation(libs.posthog)
    implementation(libs.coil)
    implementation(libs.sqlcipher)
    implementation(libs.sqlite)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}