plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mmckb.openwrtstatus"
    // Kyant0 backdrop/shapes AARs require compiling against API 36 or later.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mmckb.openwrtstatus"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Unified signing: debug and release are signed with the same MMCKB key so that
    // installing a debug build over a release build (and vice versa) never conflicts.
    signingConfigs {
        create("mmckb") {
            storeFile = file(project.findProperty("MMCKB_STORE_FILE") as String? ?: "keystore/mmckb-release.p12")
            storePassword = project.findProperty("MMCKB_STORE_PASSWORD") as String? ?: "MMCKB_openwrt_2026"
            keyAlias = project.findProperty("MMCKB_KEY_ALIAS") as String? ?: "mmckb"
            keyPassword = project.findProperty("MMCKB_KEY_PASSWORD") as String? ?: "MMCKB_openwrt_2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("mmckb")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("mmckb")
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

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Liquid glass / backdrop effects (Kyant0, Apache-2.0, Maven Central)
    implementation("io.github.kyant0:backdrop:1.0.6")
    implementation("io.github.kyant0:shapes:1.2.0")

    // SSH remote shell (maintained JSch fork, keeps the com.jcraft.jsch API)
    implementation("com.github.mwiede:jsch:0.2.22")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
