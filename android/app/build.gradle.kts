plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.airgesture.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.airgesture.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.0.0"
        ndk {
            // 只打主流 64 位 ARM（目标机型），显著减小 APK 体积
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // 不用系统用户目录里的默认 debug.keystore（当前环境对 %USERPROFILE%\.android 无写权限），
    // 改用项目内自带的 debug 签名，保证可重复构建、可安装。
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
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
        buildConfig = false
    }
}

dependencies {
    val cameraX = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    testImplementation("junit:junit:4.13.2")
}


