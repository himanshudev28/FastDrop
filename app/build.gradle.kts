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
        // Stay on 35 for now. Android 17 enforces ACCESS_LOCAL_NETWORK (runtime, NEARBY_DEVICES group)
        // only for targetSdk >= 37. Already wired: manifest declaration (placeholder below, flips
        // automatically) and the first-launch request (LocalNetwork.kt + MainActivity.onCreate).
        // Left before bumping to 37: (1) test on a real Android 17 device that discovery (UDP
        // multicast), the TCP server and sends still work with the permission granted, denied and
        // revoked (Discovery/Server retry loops should recover after a grant, unverified);
        // (2) add a denied-state banner/explanation in the UI; (3) re-check the other 36/37 behaviour
        // changes; (4) bump compileSdk to 37 if you want the Manifest.permission constant.
        targetSdk = 35
        manifestPlaceholders["localNetworkPermission"] =
            if (targetSdk!! >= 37) "android.permission.ACCESS_LOCAL_NETWORK" else "android.permission.INTERNET"
        versionCode = 4
        versionName = "2.1.1"
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
    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM tests (the android.jar one is a stub that throws).
    testImplementation("org.json:json:20240303")
}
