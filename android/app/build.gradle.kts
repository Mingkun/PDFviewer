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
        versionCode = 192
        versionName = "4.96"
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
    implementation("androidx.core:core:1.13.1")
}
