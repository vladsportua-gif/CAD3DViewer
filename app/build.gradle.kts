plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.cadviewer.mobile"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.cadviewer.mobile"
        minSdk = 24
        targetSdk = 35
        versionCode = 3
        versionName = "2.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
