plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fastdrop.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fastdrop.app"
        minSdk = 21          // works on old Android TVs too
        // Stay on 35: targeting 37 enables Android 17's local-network permission, which must be
        // requested before any LAN / Wi-Fi Direct socket. Wire that in before bumping.
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"
    }

    // CI (GitHub Actions) passes a keystore through these env vars. Locally they're unset,
    // so release builds fall back to the debug key and still install directly.
    val ksFile = System.getenv("FASTDROP_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (ksFile != null) create("release") {
            storeFile = ksFile
            storePassword = System.getenv("FASTDROP_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("FASTDROP_KEY_ALIAS")
            keyPassword = System.getenv("FASTDROP_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // QR codes (encode + decode). Pure Java, no AndroidX; only uses APIs present on Android 5.
    implementation("com.google.zxing:core:3.5.4")
}
