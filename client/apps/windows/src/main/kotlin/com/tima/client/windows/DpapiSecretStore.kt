package com.tima.client.windows

import com.sun.jna.platform.win32.Crypt32Util
import com.tima.client.platform.SecureSecretStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class DpapiSecretStore(
    private val directory: Path = Path.of(
        System.getenv("LOCALAPPDATA")
            ?: throw IllegalStateException("LOCALAPPDATA is unavailable"),
        "MessNC",
        "secrets",
    ),
) : SecureSecretStore {
    init {
        check(System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "DPAPI storage is available only on Windows"
        }
        Files.createDirectories(directory)
    }

    override suspend fun put(alias: String, secret: ByteArray) {
        require(secret.isNotEmpty())
        val destination = path(alias)
        val encrypted = Crypt32Util.cryptProtectData(secret)
        val temporary = Files.createTempFile(directory, "$alias-", ".tmp")
        try {
            Files.write(temporary, encrypted)
            Files.move(
                temporary,
                destination,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            encrypted.fill(0)
            Files.deleteIfExists(temporary)
        }
    }

    override suspend fun get(alias: String): ByteArray? {
        val source = path(alias)
        if (!Files.isRegularFile(source)) return null
        val encrypted = Files.readAllBytes(source)
        return try {
            Crypt32Util.cryptUnprotectData(encrypted)
        } finally {
            encrypted.fill(0)
        }
    }

    override suspend fun delete(alias: String) {
        Files.deleteIfExists(path(alias))
    }

    private fun path(alias: String): Path {
        require(alias.matches(Regex("[a-z0-9-]{1,100}"))) { "invalid secret alias" }
        return directory.resolve("$alias.dpapi")
    }
}
