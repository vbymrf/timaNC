import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    val frameworkName = "TimaIosApp"
    val xcframework = XCFramework(frameworkName)

    // Executes common iOS-facing presenter/gate tests on credential-free CI hosts.
    jvm()
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = frameworkName
            isStatic = true
            binaryOption("bundleId", "com.tima.client.ios.shared")
            export(project(":modules:core:core-data"))
            export(project(":modules:core:core-media"))
            xcframework.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":modules:core:core-data"))
            api(project(":modules:core:core-database"))
            api(project(":modules:core:core-media"))
            api(project(":modules:core:core-network"))
            api(project(":modules:core:core-sync"))
            api(project(":modules:messenger-crypto"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native.driver)
        }
    }
}
