plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.buggygamesyt.theclaww"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.buggygamesyt.theclaww"
        minSdk = 26
        targetSdk = 34
        // Always newer than the last build, so Android installs each release as an update.
        versionCode = ((System.currentTimeMillis() / 1000 - 1767225600L) / 60).toInt()
        versionName = "0.3.0-android"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    // One fixed signing key for every build (CI runners make a new random debug key each time, and Android
    // refuses to update an app signed with a different key).
    signingConfigs {
        create("claww") {
            storeFile = rootProject.file("claww.p12"); storeType = "pkcs12"
            storePassword = "theclaww"; keyAlias = "claww"; keyPassword = "theclaww"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("claww") }
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("claww") }
    }
    // claw runs as a native executable from nativeLibraryDir, so libs must be extracted.
    packaging { jniLibs { useLegacyPackaging = true } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
