plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(17)
    jvm()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":modules:core:core-domain"))
            implementation(project(":modules:messenger-crypto"))
        }
    }
}
