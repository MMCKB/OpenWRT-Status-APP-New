import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 签名凭据解析（参照 InstallerX-Revived 的模式）：优先本地 keystore.properties
// （gitignore），CI 上来自 KEYSTORE_* 环境变量（GitHub Secrets 注入）。
// 仓库本身不包含任何签名密钥。
val keystoreProperties = Properties().apply {
    val propertiesFile = rootProject.file("keystore.properties")
    if (propertiesFile.exists()) {
        propertiesFile.inputStream().use { load(it) }
    }
}
val signingStoreFile = keystoreProperties.getProperty("storeFile") ?: System.getenv("KEYSTORE_FILE")
val signingStorePassword = keystoreProperties.getProperty("storePassword") ?: System.getenv("KEYSTORE_PASSWORD")
val signingKeyAlias = keystoreProperties.getProperty("keyAlias") ?: System.getenv("KEY_ALIAS")
val signingKeyPassword = keystoreProperties.getProperty("keyPassword") ?: System.getenv("KEY_PASSWORD")
val hasCustomSigning = listOf(signingStoreFile, signingStorePassword, signingKeyAlias, signingKeyPassword).all { !it.isNullOrBlank() }

android {
    namespace = "com.mmckb.openwrtstatus"
    // Kyant0 backdrop 2.x and the current androidx stack require compiling against API 37.
    compileSdk = 37
    compileSdkExtension = 2

    defaultConfig {
        // 无私钥的构建回退 debug 签名并使用独立 applicationId，不会与正式版互相覆盖。
        applicationId = if (hasCustomSigning) {
            "com.mmckb.openwrtstatus"
        } else {
            "com.mmckb.openwrtstatus.dev"
        }
        minSdk = 24
        targetSdk = 37
        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasCustomSigning) {
            create("mmckb") {
                storeFile = file(signingStoreFile!!)
                storePassword = signingStorePassword!!
                keyAlias = signingKeyAlias!!
                keyPassword = signingKeyPassword!!
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasCustomSigning) {
                signingConfigs.getByName("mmckb")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            signingConfig = if (hasCustomSigning) {
                signingConfigs.getByName("mmckb")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    // 按架构拆分：一次构建产出 4 个 APK（arm64-v8a 64 位 / armeabi-v7a 32 位 / x86_64 / x86），
    // 不再产出合并的 universal APK。
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // BuildConfig.VERSION_NAME is read by the About screen.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// AGP 9 built-in Kotlin: jvmTarget follows compileOptions (17) automatically.

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")

    // Liquid glass / backdrop effects (Kyant0, Apache-2.0, Maven Central)
    implementation("io.github.kyant0:backdrop:2.0.1")
    implementation("io.github.kyant0:shapes:1.2.1")

    // SSH remote shell (maintained JSch fork, keeps the com.jcraft.jsch API)
    implementation("com.github.mwiede:jsch:2.28.7")

    // WiFi 分享二维码生成（ZXing core，纯 Java 无传递依赖，Apache-2.0）
    implementation("com.google.zxing:core:3.5.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
