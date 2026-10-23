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
        // GitHub Actions pone el número de cada compilación; así la laptop y el teléfono
        // saben cuál es más nueva (las versiones de antes llegaban hasta el 3).
        versionCode = System.getenv("ECO_VERSION_CODE")?.toIntOrNull() ?: 100
        versionName = System.getenv("ECO_VERSION_NAME") ?: "1.3-local"
        // Solo los procesadores de teléfonos reales: así el reconocedor de voz no infla la app.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
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
    // «Eco, recuérdame…»: reconocimiento de voz sin internet (Vosk). El modelo en español no va en la
    // app: el teléfono lo baja una vez desde tu laptop.
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
}
