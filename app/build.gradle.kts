import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.avatarmocap"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.avatarmocap"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-face-experimental"
    }

    buildTypes {
        release {
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
    }
    // El modelo .task de MediaPipe no debe comprimirse dentro del APK.
    androidResources {
        noCompress += listOf("task")
    }
}

dependencies {
    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Cámara
    val cameraX = "1.4.1"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    // Tracking facial (478 landmarks + 52 blendshapes + matriz de pose)
    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // WebSocket para la API de VTube Studio
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

// Descarga automática del modelo de MediaPipe la primera vez que se compila.
val faceModelUrl =
    "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task"
val faceModelFile = layout.projectDirectory.file("src/main/assets/face_landmarker.task")

val downloadFaceModel by tasks.registering {
    description = "Descarga face_landmarker.task en assets si no existe"
    outputs.file(faceModelFile)
    doLast {
        val f = faceModelFile.asFile
        if (!f.exists()) {
            f.parentFile.mkdirs()
            logger.lifecycle("Descargando modelo de MediaPipe -> ${f.path}")
            URI(faceModelUrl).toURL().openStream().use { input ->
                f.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }
}

tasks.named("preBuild") { dependsOn(downloadFaceModel) }
