import java.time.LocalDate
import java.time.format.DateTimeFormatter

// 版本双轨制（与桌面 set-version.mjs 同源同日）：
// versionName / APK 文件名用补零显示版 vYY.MM.DD（如 v26.09.07，安卓无 semver 约束，与文件名逐字符一致）；
// versionCode 取日期整数（260907），单调递增可覆盖升级。
// 桌面内部烧入 exe 的是同日的 semver 形式（26.9.7），二者恒等映射。
val buildDisplay: String = LocalDate.now().format(DateTimeFormatter.ofPattern("'v'yy.MM.dd"))
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
        // minSdk = 21（Android 5.0）：用户实测 TCL 65V6-A65F 电视是 Android 5.0，
        // 原来的 24 会让系统直接拒绝安装（INSTALL_FAILED_OLDER_SDK）。
        // 21 是能落到的最低点：activity-compose:1.9.3 + 整棵 Compose 依赖树都硬性要求 21，
        // 降到 20 会在 manifest merger 阶段直接失败。所有 minSdk 24+ 的 API 调用点
        // （getSystemService(Class)、startForegroundService、checkSelfPermission 等）均已加版本守卫。
        minSdk = 21
        targetSdk = 34
        versionCode = buildDateCode
        versionName = buildDisplay
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

    lint {
        // 本项目不使用 Fragment（入口全是 ComponentActivity + Compose），
        // InvalidFragmentVersionForActivityResult 在这里是误报。但它默认是 error，
        // 会让 assembleRelease 报BUILD FAILED，反而掩盖真正的 NewApi 问题，故显式关闭。
        disable += "InvalidFragmentVersionForActivityResult"
        // NewApi 保持 error 并显式声明：这是低版本设备闪退的第一道防线。
        // 2026-10-06 降到minSdk 21 时靠它一次抓出 9 处高版本 API 漏守卫的问题。
        error += "NewApi"
    }

    applicationVariants.all {
        outputs.all {
            val out = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            out.outputFileName = "local-sharing_${buildDisplay}.apk"
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

    // 电视接收模式：内嵌 HTTP/WS 服务器 + 大屏二维码生成
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")
    implementation("com.google.zxing:core:3.5.3")
}
