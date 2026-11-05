plugins {
    id("com.android.application")
}

android {
    namespace = "com.hoyo.ghostroot"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hoyo.ghostroot"
        minSdk = 26
        targetSdk = 34
        versionCode = 56
        versionName = "2.40"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.3.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
