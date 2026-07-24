package com.tima.client.windows

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.tima.client.database.TimaDatabase
import com.tima.client.media.MediaVariantName
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.sql.DriverManager
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsPrivateImageMediaTest {
    @Test
    fun missingOrMalformedDevelopmentBuildPropertyFailsClosed() {
        assertFalse(windowsDevelopmentEscrowBuildAllowed(null))
        assertFalse(windowsDevelopmentEscrowBuildAllowed(""))
        assertFalse(windowsDevelopmentEscrowBuildAllowed("TRUE"))
        assertTrue(windowsDevelopmentEscrowBuildAllowed("true"))
    }

    @Test
    fun normalizerProducesExactBoundedJpegVariantsWithoutUpscaling() = runBlocking {
        val source = BufferedImage(640, 320, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().also {
                it.color = Color.BLUE
                it.fillRect(0, 0, width, height)
                it.dispose()
            }
        }
        val input = ByteArrayOutputStream().also { ImageIO.write(source, "png", it) }.toByteArray()

        val result = WindowsImageNormalizer().normalize(input)

        assertEquals(MediaVariantName.entries, result.variants.map { it.name })
        assertEquals(listOf(40 to 20, 320 to 160, 640 to 320), result.variants.map { it.width to it.height })
        assertTrue(result.variants.all { it.jpeg[0] == 0xff.toByte() && it.jpeg[1] == 0xd8.toByte() })
        result.variants.forEach { it.jpeg.fill(0) }
        input.fill(0)
    }

    @Test
    fun boundedReaderRejectsWithoutReturningPartialPlaintext() {
        assertEquals(
            listOf<Byte>(1, 2, 3),
            readBounded(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3).toList(),
        )
        assertFails { readBounded(ByteArrayInputStream(ByteArray(5)), 4) }
    }

    @Test
    fun existingMessagingDatabaseMigratesMediaCiphertextTable() {
        val path = Files.createTempFile("tima-windows-media-migration", ".sqlite")
        val url = "jdbc:sqlite:${path.toAbsolutePath()}"
        JdbcSqliteDriver(url).use { driver ->
            TimaDatabase.Schema.create(driver)
            driver.execute(null, "DROP TABLE media_cipher_blob", 0)
        }
        DriverManager.getConnection(url).use {
            it.createStatement().use { statement -> statement.execute("PRAGMA user_version = 1") }
        }

        WindowsPhase1Runtime.openDatabase(path).use { driver ->
            assertTrue(
                TimaDatabase(driver).phase1Queries
                    .selectMediaCipherBlob("missing")
                    .executeAsOneOrNull() == null,
            )
        }
        DriverManager.getConnection(url).use {
            it.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(TimaDatabase.Schema.version, rows.getLong(1))
                }
            }
        }
        Files.deleteIfExists(path)
    }
}
