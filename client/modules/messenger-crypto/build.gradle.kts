plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(17)
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(project(":modules:core:core-domain"))
            implementation(libs.kodium)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
