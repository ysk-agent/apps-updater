plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.moriya.appsupdater"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.moriya.appsupdater"
        minSdk = 29
        targetSdk = 34
        versionCode = 20
        versionName = "0.2.5"
    }

    // Pinned signing key, same pattern as yt-player / phone-node-waker:
    // CI decodes the keystore from the SIGNING_KEYSTORE_B64 repo secret
    // and points SIGNING_KEYSTORE at it, so every build carries the
    // same certificate and can overwrite-install over previous builds.
    signingConfigs {
        create("pinned") {
            val keystorePath = System.getenv("SIGNING_KEYSTORE")
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_STORE_PASS")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASS")
            }
        }
    }

    buildTypes {
        debug {
            if (System.getenv("SIGNING_KEYSTORE") != null) {
                signingConfig = signingConfigs.getByName("pinned")
            }
        }
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// The app was framework-APIs-only until v0.2.3. v0.2.4 tried
// OkHttp for the download path; v0.2.5 uses Cronet (the Chromium
// network stack, provided by Google Play services) instead.
// Measured on the user's moto g05 (2026-11-21): both the platform
// HttpURLConnection stack and OkHttp plateau at ~4–5 MB/s for
// release assets — single-stream and 8-way parallel alike, app
// CPU idle, WiFi negotiated at 390 Mbps — while Chrome pulled
// the same asset at ~21 MB/s on the same phone in the same
// minutes and a wired Mac on the same gateway at 18–56 MB/s.
// The difference is the browser's own stack (QUIC/BBR/socket
// tuning), so the download path now uses that same stack. The
// scan/API path keeps HttpURLConnection (small JSON, unaffected).
dependencies {
    implementation("com.google.android.gms:play-services-cronet:18.1.1")
}
