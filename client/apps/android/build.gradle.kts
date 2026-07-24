plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.services) apply false
}

if (file("google-services.json").isFile) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.tima.client.android"
    compileSdk = 35
    buildFeatures {
        buildConfig = true
    }

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
        debug {
            val developmentAuth = providers.gradleProperty("tima.android.enableDevelopmentAuth")
                .orNull?.toBooleanStrictOrNull() ?: false
            buildConfigField("boolean", "ENABLE_DEVELOPMENT_AUTH", developmentAuth.toString())
        }
        release {
            isMinifyEnabled = false
            buildConfigField("boolean", "ENABLE_DEVELOPMENT_AUTH", "false")
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
    implementation(project(":modules:core:core-data"))
    implementation(project(":modules:core:core-network"))
    implementation(project(":modules:core:core-sync"))
    implementation(project(":modules:messenger-crypto"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.play.integrity)
    implementation(libs.firebase.messaging)
    testImplementation(kotlin("test"))
}
