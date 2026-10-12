plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.hplay.receiver"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hplay.receiver"
        minSdk = 23 // Android TV 6+ y Fire TV (Fire OS 6+)
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    val ksPath = System.getenv("HPLAY_KEYSTORE")
    signingConfigs {
        if (ksPath != null) create("release") {
            storeFile = file(ksPath)
            storePassword = System.getenv("HPLAY_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("HPLAY_KEY_ALIAS")
            keyPassword = System.getenv("HPLAY_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (ksPath != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
