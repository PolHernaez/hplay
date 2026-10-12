import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Firma de release: C:/Users/Pol/HPlay-claus/signatura.properties (o la ruta de HPLAY_SIGNING). Nunca va al repo.
// Sin ese fichero (CI, otro PC) la release sale sin firmar y build-apk.sh se niega a publicarla.
val hplaySigning = Properties().apply {
    val f = file(System.getenv("HPLAY_SIGNING") ?: "C:/Users/Pol/HPlay-claus/signatura.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.hplay.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hplay.app"
        minSdk = 29
        targetSdk = 35
        versionCode = providers.gradleProperty("hplay.versionCode").get().toInt()
        versionName = providers.gradleProperty("hplay.versionName").get()
    }

    signingConfigs {
        if (!hplaySigning.isEmpty) create("release") {
            storeFile = file(hplaySigning.getProperty("storeFile"))
            storePassword = hplaySigning.getProperty("storePassword")
            keyAlias = hplaySigning.getProperty("keyAlias")
            keyPassword = hplaySigning.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (!hplaySigning.isEmpty) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":updater"))
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
