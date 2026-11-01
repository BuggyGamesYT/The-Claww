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
        versionCode = 6
        versionName = "0.2.3-android"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    buildTypes {
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("debug") }
    }
    // claw runs as a native executable from nativeLibraryDir, so libs must be extracted.
    packaging { jniLibs { useLegacyPackaging = true } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
