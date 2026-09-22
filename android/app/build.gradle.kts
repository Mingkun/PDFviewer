plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mingkun.pdfviewer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mingkun.pdfviewer"
        minSdk = 26
        targetSdk = 34
        versionCode = 13
        versionName = "2.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
}
