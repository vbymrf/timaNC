import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    val frameworkName = "TimaIosApp"
    val xcframework = XCFramework(frameworkName)

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = frameworkName
            isStatic = true
            binaryOption("bundleId", "com.tima.client.ios.shared")
            xcframework.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":modules:core:core-network"))
            api(project(":modules:core:core-sync"))
            api(project(":modules:messenger-crypto"))
            implementation(libs.kotlinx.coroutines.core)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}
