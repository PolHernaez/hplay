plugins {
    alias(libs.plugins.android.library)
}

// Actualizador compartido por la app del móvil y la receptora de Android TV / Fire TV.
android {
    namespace = "com.hplay.updater"
    compileSdk = 37
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.org.json) // org.json de Android es un stub en los tests de JVM
}
