plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Versión en un solo lugar.
val appVersionName = "1.0"
val appVersionCode = 1

android {
    namespace = "com.hplay.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hplay.app"
        minSdk = 29
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    // Firma de release desde variables de entorno (GitHub Actions). Sin ellas, release queda sin firmar.
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
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
    implementation(libs.zxing.core)
    testImplementation(libs.junit)
}
