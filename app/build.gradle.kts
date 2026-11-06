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
        versionCode = 68
        versionName = "2.52"
    }

    buildTypes {
        getByName("release") {
            /* 本机自用的提权工具：release 变体沿用 debug key 签名，
             * 与设备上已装的 debug 包同证书 —— 可直接 -r 覆盖安装，
             * 不会丢 KernelSU 超级用户授权 / Shizuku 授权 / App 数据。
             * 若将来需要对外分发，替换为独立 keystore（签名变更后
             * 必须先卸载，并在手机上重新点一次两项授权）。 */
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false   // R8 会破坏 Shizuku/hidden API 反射链
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
