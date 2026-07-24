plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
}

kotlin {
    jvmToolchain(17)
    jvm()
    androidTarget()
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":modules:core:core-domain"))
            implementation(project(":modules:core:core-network"))
            implementation(project(":modules:messenger-crypto"))
            implementation(libs.kodium)
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.mock)
        }
    }
}

android {
    namespace = "com.tima.client.media"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
}
