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
        versionCode = 3
        versionName = "0.1.2"
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

// No dependencies on purpose: the whole app is framework APIs only
// (SAF / DocumentsContract, MediaStore, PackageInstaller), which keeps
// the APK small and the build free of dependency surprises.
dependencies {
}
