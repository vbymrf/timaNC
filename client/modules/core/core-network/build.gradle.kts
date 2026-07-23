plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(17)
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(project(":modules:core:core-domain"))
            implementation(project(":modules:messenger-crypto"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
