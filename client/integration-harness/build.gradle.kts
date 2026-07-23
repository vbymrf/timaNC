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
        jvmTest.dependencies {
            implementation(project(":modules:core:core-network"))
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
        }
    }
}

tasks.withType<Test>().configureEach {
    if (System.getenv("TIMA_REQUIRE_HTTP_E2E") == "true") {
        outputs.upToDateWhen { false }
    }
}
