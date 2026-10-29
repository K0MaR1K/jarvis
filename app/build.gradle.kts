plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kholmsk.jarvis"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kholmsk.jarvis"
        minSdk = 27          // Honor 10: Android 8.1 из коробки, у нас Android 10 (EMUI 12)
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    androidResources {
        // Модели MediaPipe должны лежать в APK несжатыми, иначе не читаются
        noCompress += listOf("tflite", "task")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    val camerax = "1.4.2"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")

    // MediaPipe: детектор лица + трекер рук, всё офлайн
    implementation("com.google.mediapipe:tasks-vision:0.10.29")
}
