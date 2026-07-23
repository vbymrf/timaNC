plugins {
    kotlin("multiplatform") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
    id("com.squareup.wire") version "6.4.0"
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
    jvm()

    sourceSets {
        commonMain {
            kotlin.srcDir("proto")
            kotlin.srcDir("openapi/src/commonMain/kotlin")
            dependencies {
                implementation("com.squareup.wire:wire-runtime:6.4.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
            }
        }
    }
}

wire {
    sourcePath {
        srcDir("../../schema/proto")
    }
    kotlin {
        out = file("proto").absolutePath
        javaInterop = false
    }
}
