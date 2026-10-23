plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.fastdownload.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.fastdownload.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}
