plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hoyo.ghostroot"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hoyo.ghostroot"
        minSdk = 26
        targetSdk = 34
        /* v80: Compose 版 UI + ZN 单例收敛根治（zygiskAutoActivate：
         * SIGKILL 收敛、收敛后重读 zygote_states、成功判据收紧为单例）。
         * 设备侧沿用项目自有运行时资产，APK 版本不与任何实验构建绑定。 */
        versionCode = 80
        versionName = "2.64"
        testInstrumentationRunner = "com.hoyo.ghostroot.LsposedSmokeTest"
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        aidl = true
        buildConfig = true
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.3.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
