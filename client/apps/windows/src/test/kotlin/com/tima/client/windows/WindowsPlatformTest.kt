package com.tima.client.windows

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WindowsPlatformTest {
    @Test
    fun linkGatewayRejectsCleartextService() {
        assertFailsWith<IllegalArgumentException> {
            HttpWindowsLinkGateway("http://example.test")
        }
    }

    @Test
    fun dpapiRoundTripAndDelete() = runBlocking {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return@runBlocking
        val directory = Files.createTempDirectory("messnc-dpapi-test")
        try {
            val store = DpapiSecretStore(directory)
            val secret = ByteArray(32) { it.toByte() }
            store.put("test-secret", secret)
            assertContentEquals(secret, store.get("test-secret"))
            store.delete("test-secret")
            assertNull(store.get("test-secret"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
