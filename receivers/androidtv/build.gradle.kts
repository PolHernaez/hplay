import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Firma de release: C:/Users/Pol/HPlay-claus/signatura.properties (o la ruta de HPLAY_SIGNING). Nunca va al repo.
// Sin ese fichero (CI, otro PC) la release sale sin firmar y build-apk.sh se niega a publicarla.
val hplaySigning = Properties().apply {
    val f = file(System.getenv("HPLAY_SIGNING") ?: "C:/Users/Pol/HPlay-claus/signatura.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    sourceSets["main"].kotlin.srcDir("../../shared/dlna") // Renderer/Upnp/DlnaDevice compartidos con el puente del móvil
    namespace = "com.hplay.receiver"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hplay.receiver"
        minSdk = 23 // Android TV 6+ y Fire TV (Fire OS 6+)
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
    // Reproductor compartido con el receptor de Windows (player.html + hls.js).
    sourceSets["main"].assets.srcDirs("src/main/assets", "../shared")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":updater"))
}
