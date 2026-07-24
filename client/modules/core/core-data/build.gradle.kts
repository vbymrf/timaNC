plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
}

layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("core-data"))

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
            api(project(":modules:core:core-network"))
            api(project(":modules:core:core-media"))
            implementation(project(":modules:core:core-database"))
            implementation(project(":modules:messenger-crypto"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.mock)
        }
        jvmTest.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
    }
}

android {
    namespace = "com.tima.client.data"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }
}
