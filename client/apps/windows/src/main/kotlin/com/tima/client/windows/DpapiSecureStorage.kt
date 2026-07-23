package com.tima.client.windows

import com.sun.jna.platform.win32.Crypt32Util
import com.tima.client.network.PlatformServiceUnavailableException
import com.tima.client.network.SecureStorage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class DpapiSecureStorage(
    private val directory: Path = defaultDirectory(),
) : SecureStorage {
    init {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            throw PlatformServiceUnavailableException("Windows DPAPI")
        }
        runCatching { Files.createDirectories(directory) }
            .getOrElse { throw PlatformServiceUnavailableException("Windows credential directory", it) }
    }

    override suspend fun read(key: String): ByteArray? {
        val file = fileFor(key)
        if (!Files.exists(file)) return null
        return runCatching {
            Crypt32Util.cryptUnprotectData(Files.readAllBytes(file))
        }.getOrElse { throw PlatformServiceUnavailableException("Windows DPAPI decrypt", it) }
    }

    override suspend fun write(key: String, value: ByteArray) {
        require(value.isNotEmpty()) { "secure values must not be empty" }
        runCatching {
            val encrypted = Crypt32Util.cryptProtectData(value.copyOf())
            val destination = fileFor(key)
            val temporary = Files.createTempFile(directory, ".tima-", ".tmp")
            try {
                Files.write(temporary, encrypted)
                Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } finally {
                Files.deleteIfExists(temporary)
            }
        }.getOrElse { throw PlatformServiceUnavailableException("Windows DPAPI encrypt", it) }
    }

    override suspend fun delete(key: String) {
        runCatching { Files.deleteIfExists(fileFor(key)) }
            .getOrElse { throw PlatformServiceUnavailableException("Windows credential deletion", it) }
    }

    private fun fileFor(key: String): Path {
        require(key.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "invalid secure-storage key" }
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) } + ".bin"
        return directory.resolve(name)
    }

    private companion object {
        fun defaultDirectory(): Path {
            val localAppData = System.getenv("LOCALAPPDATA")
                ?: throw PlatformServiceUnavailableException("LOCALAPPDATA")
            return Path.of(localAppData, "Tima", "secure-v1")
        }
    }
}
