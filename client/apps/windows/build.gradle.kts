import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":modules:core:core-data"))
    implementation(project(":modules:core:core-database"))
    implementation(project(":modules:core:core-media"))
    implementation(project(":modules:core:core-network"))
    implementation(project(":modules:core:core-sync"))
    implementation(project(":modules:messenger-crypto"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.jna.platform)
    implementation(libs.zxing.core)
    implementation(libs.sqldelight.sqlite.driver)
    testImplementation(kotlin("test"))
}

val developmentEscrowBuild = providers.gradleProperty("tima.windows.enableDevelopmentEscrow")
    .map { it.toBooleanStrictOrNull() ?: false }
    .orElse(false)

application {
    mainClass.set("com.tima.client.windows.MainKt")
    applicationName = "Tima"
    applicationDefaultJvmArgs = listOf(
        "-Dtima.windows.developmentEscrowBuild=${developmentEscrowBuild.get()}",
    )
}

tasks.jar {
    manifest.attributes["Main-Class"] = application.mainClass.get()
}

val jpackageInput = layout.buildDirectory.dir("jpackage-input")
val jpackageOutput = layout.buildDirectory.dir("jpackage")
val msixStage = layout.buildDirectory.dir("msix-stage")

val prepareJpackageInput by tasks.registering(Sync::class) {
    dependsOn(tasks.jar)
    from(tasks.jar)
    from(configurations.runtimeClasspath)
    into(jpackageInput)
}

val packageWindowsAppImage by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Creates an unsigned local Windows application image with jpackage."
    dependsOn(prepareJpackageInput)
    onlyIf { System.getProperty("os.name").startsWith("Windows", ignoreCase = true) }
    doFirst {
        delete(jpackageOutput)
        val jpackage = file("${System.getProperty("java.home")}/bin/jpackage.exe")
        check(jpackage.isFile) { "jpackage.exe is required from a JDK 17+ installation" }
        commandLine(
            jpackage.absolutePath,
            "--type", "app-image",
            "--name", "Tima",
            "--app-version", "0.1.0",
            "--vendor", "Tima",
            "--input", jpackageInput.get().asFile.absolutePath,
            "--main-jar", tasks.jar.get().archiveFileName.get(),
            "--main-class", application.mainClass.get(),
            "--java-options",
            "-Dtima.windows.developmentEscrowBuild=${developmentEscrowBuild.get()}",
            "--dest", jpackageOutput.get().asFile.absolutePath,
        )
    }
}

val verifyWindowsAppImageConfiguration by tasks.registering {
    group = "verification"
    description = "Checks that the packaged runtime retains its fail-closed development gate."
    dependsOn(packageWindowsAppImage)
    doLast {
        val config = jpackageOutput.get().file("Tima/app/Tima.cfg").asFile
        check(config.isFile) { "jpackage runtime configuration was not generated" }
        val expected = "java-options=-Dtima.windows.developmentEscrowBuild=${developmentEscrowBuild.get()}"
        check(config.readLines().any { it.trim() == expected }) {
            "jpackage runtime configuration omitted the development escrow build gate"
        }
    }
}

val prepareMsixInputs by tasks.registering {
    group = "distribution"
    description = "Stages the checked-in MSIX manifest and deterministic placeholder assets."
    inputs.file("src/msix/Package.appxmanifest")
    outputs.dir(msixStage)
    doLast {
        val stage = msixStage.get().asFile
        delete(stage)
        copy {
            from("src/msix/Package.appxmanifest")
            into(stage)
            rename { "AppxManifest.xml" }
        }
        val assets = stage.resolve("Assets").apply { mkdirs() }
        mapOf(
            "StoreLogo.png" to 50,
            "Square44x44Logo.png" to 44,
            "Square150x150Logo.png" to 150,
        ).forEach { (name, size) ->
            val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            graphics.color = Color(49, 86, 163)
            graphics.fillRect(0, 0, size, size)
            graphics.dispose()
            ImageIO.write(image, "png", assets.resolve(name))
        }
    }
}

val packageMsixUnsigned by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Builds an unsigned MSIX; release signing is intentionally delegated to CI."
    dependsOn(verifyWindowsAppImageConfiguration, prepareMsixInputs)
    onlyIf { System.getProperty("os.name").startsWith("Windows", ignoreCase = true) }
    doFirst {
        val stage = msixStage.get().asFile
        copy {
            from(jpackageOutput.map { it.dir("Tima") })
            into(stage.resolve("app"))
        }
        val kits = file("${System.getenv("ProgramFiles(x86)")}/Windows Kits/10/bin")
        val makeAppx = kits.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.name }
            ?.map { it.resolve("x64/makeappx.exe") }
            ?.firstOrNull { it.isFile }
            ?: error("Windows SDK makeappx.exe was not found")
        val output = layout.buildDirectory.file("distributions/Tima-0.1.0-unsigned.msix").get().asFile
        output.parentFile.mkdirs()
        commandLine(
            makeAppx.absolutePath,
            "pack",
            "/d", stage.absolutePath,
            "/p", output.absolutePath,
            "/o",
        )
    }
}
