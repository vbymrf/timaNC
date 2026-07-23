plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val playCloudProjectNumber = providers.environmentVariable("TIMA_PLAY_CLOUD_PROJECT_NUMBER")
    .orElse("0")
val firebaseProjectId = providers.environmentVariable("TIMA_FIREBASE_PROJECT_ID").orElse("")
val firebaseApplicationId = providers.environmentVariable("TIMA_FIREBASE_APPLICATION_ID").orElse("")
val firebaseSenderId = providers.environmentVariable("TIMA_FIREBASE_SENDER_ID").orElse("")

android {
    namespace = "com.tima.client.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tima.messnc"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("long", "PLAY_CLOUD_PROJECT_NUMBER", "${playCloudProjectNumber.get()}L")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${firebaseProjectId.get()}\"")
        buildConfigField("String", "FIREBASE_APPLICATION_ID", "\"${firebaseApplicationId.get()}\"")
        buildConfigField("String", "FIREBASE_SENDER_ID", "\"${firebaseSenderId.get()}\"")
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":modules:platform:platform-core"))
    implementation(libs.play.integrity)
    implementation(libs.firebase.messaging)
    implementation(libs.kotlinx.coroutines.core)
}
