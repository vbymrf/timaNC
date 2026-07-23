plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.tima.client.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tima.client"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        manifestPlaceholders["timaBaseUrl"] =
            providers.gradleProperty("tima.android.baseUrl").orNull ?: ""
        manifestPlaceholders["timaIntegrityProjectNumber"] =
            providers.gradleProperty("tima.android.integrityProjectNumber").orNull ?: "0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // CI supplies the release signing configuration.
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":modules:core:core-network"))
    implementation(project(":modules:messenger-crypto"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.play.integrity)
    implementation(libs.firebase.messaging)
}
