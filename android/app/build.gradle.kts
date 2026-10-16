import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.blackholecode.eco"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.blackholecode.eco"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
    }

    // Llave fija: así cada APK nuevo se instala encima del anterior sin borrar tus ajustes.
    signingConfigs {
        getByName("debug") {
            storeFile = file("eco-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Escáner de QR de Google Play Services: sin permiso de cámara y sin pantalla propia que mantener.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}
