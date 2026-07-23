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

application {
    mainClass = "com.tima.client.windows.MainKt"
    applicationName = "MessNC"
}

dependencies {
    implementation(project(":modules:platform:platform-core"))
    implementation(project(":modules:messenger-crypto"))
    implementation(libs.jna.platform)
    implementation(libs.zxing.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}

val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val appImageDir = layout.buildDirectory.dir("windows-app-image")
val msixLayoutDir = layout.buildDirectory.dir("msix-layout")

val windowsAppImage by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Creates the local Windows app image used by MSIX packaging."
    dependsOn(tasks.installDist)
    onlyIf { isWindows }
    outputs.dir(appImageDir)
    doFirst {
        delete(appImageDir)
        val jpackage = javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(17)
        }.get().metadata.installationPath.file("bin/jpackage.exe").asFile
        check(jpackage.isFile) { "jpackage.exe is required for the Windows app image" }
        commandLine(
            jpackage,
            "--type", "app-image",
            "--name", "MessNC",
            "--dest", appImageDir.get().asFile,
            "--input", layout.buildDirectory.dir("install/MessNC/lib").get().asFile,
            "--main-jar", tasks.jar.get().archiveFileName.get(),
            "--main-class", application.mainClass.get(),
            "--app-version", "0.1.0",
        )
    }
}

val generateMsixAssets by tasks.registering {
    outputs.dir(layout.buildDirectory.dir("generated-msix-assets"))
    doLast {
        val output = layout.buildDirectory.dir("generated-msix-assets").get().asFile
        output.mkdirs()
        mapOf(
            "StoreLogo.png" to 50,
            "Square150x150Logo.png" to 150,
            "Square44x44Logo.png" to 44,
        ).forEach { (name, size) ->
            val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            graphics.color = Color(26, 35, 50)
            graphics.fillRect(0, 0, size, size)
            graphics.color = Color(65, 165, 245)
            graphics.fillOval(size / 5, size / 5, size * 3 / 5, size * 3 / 5)
            graphics.dispose()
            ImageIO.write(image, "png", output.resolve(name))
        }
    }
}

val prepareMsixLayout by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Prepares an unsigned MSIX layout locally."
    dependsOn(windowsAppImage, generateMsixAssets)
    onlyIf { isWindows }
    into(msixLayoutDir)
    from(appImageDir.map { it.dir("MessNC") }) { into("MessNC") }
    from("packaging/AppxManifest.xml")
    from(layout.buildDirectory.dir("generated-msix-assets")) { into("Assets") }
}

val packageUnsignedMsix by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Packages a local unsigned MSIX with Windows makeappx.exe."
    dependsOn(prepareMsixLayout)
    onlyIf { isWindows }
    val output = layout.buildDirectory.file("distributions/MessNC-0.1.0-unsigned.msix")
    inputs.dir(msixLayoutDir)
    outputs.file(output)
    doFirst {
        output.get().asFile.parentFile.mkdirs()
        commandLine(
            "makeappx.exe", "pack", "/o",
            "/d", msixLayoutDir.get().asFile.absolutePath,
            "/p", output.get().asFile.absolutePath,
        )
    }
}

tasks.register("verifyUnsignedMsixInputs") {
    group = "verification"
    description = "Checks local MSIX inputs without signing or Windows SDK tools."
    inputs.file("packaging/AppxManifest.xml")
    doLast {
        val manifest = file("packaging/AppxManifest.xml").readText()
        check("MessNC\\MessNC.exe" in manifest)
        check("runFullTrust" in manifest)
        check("Publisher=\"CN=MessNC Development\"" in manifest)
    }
}

tasks.test {
    useJUnitPlatform()
}
