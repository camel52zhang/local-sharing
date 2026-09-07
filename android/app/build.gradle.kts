import java.time.LocalDate

// 版本号 = 构建当日日期的 semver 形式（YY.M.D，去前导零，与桌面端 set-version.mjs 同规则），
// 全链路逐字符一致：versionName = APK 文件名 = 桌面 exe 内嵌版本/文件名。
// 例：2026-09-07 -> 26.9.7 -> local-sharing_26.9.7.apk；versionCode 取日期整数（260907），单调递增可覆盖升级
val buildVersion: String = LocalDate.now().let { "${it.year % 100}.${it.monthValue}.${it.dayOfMonth}" }
val buildDateCode: Int = LocalDate.now().let { (it.year % 100) * 10000 + it.monthValue * 100 + it.dayOfMonth }

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.localsharing.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.localsharing.app"
        minSdk = 24
        targetSdk = 34
        versionCode = buildDateCode
        versionName = buildVersion
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
        release {
            isDebuggable = false
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    applicationVariants.all {
        outputs.all {
            val out = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            out.outputFileName = "local-sharing_${buildVersion}.apk"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    implementation("androidx.core:core-ktx:1.13.1")

    // 网络：HTTP + WebSocket
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 扫码：CameraX + ML Kit Barcode
    implementation("androidx.camera:camera-core:1.3.3")
    implementation("androidx.camera:camera-camera2:1.3.3")
    implementation("androidx.camera:camera-lifecycle:1.3.3")
    implementation("androidx.camera:camera-view:1.3.3")
    implementation("com.google.mlkit:barcode-scanning:17.2.0")

    // 遍历文件夹（SAF tree）
    implementation("androidx.documentfile:documentfile:1.0.1")
}
