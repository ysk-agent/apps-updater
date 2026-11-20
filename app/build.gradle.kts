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
        versionCode = 19
        versionName = "0.2.4"
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

// The app was framework-APIs-only until v0.2.3. v0.2.4 adds
// OkHttp for the asset download path only: on a real device the
// platform HttpURLConnection stack plateaued at ~5 MB/s for
// release assets (single and 8-way parallel alike) while Chrome
// pulled the same asset at ~21 MB/s on the same phone and WiFi,
// so the transport, not the network, was the bottleneck. The
// scan/API path keeps using HttpURLConnection (small JSON,
// unaffected).
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
