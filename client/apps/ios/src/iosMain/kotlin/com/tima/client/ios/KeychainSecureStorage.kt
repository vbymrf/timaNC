@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.cinterop.BetaInteropApi::class,
)

package com.tima.client.ios

import com.tima.client.network.PlatformServiceUnavailableException
import com.tima.client.network.SecureStorage
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryCreate
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

class KeychainSecureStorage(
    private val service: String = "com.tima.client.phase1",
) : SecureStorage {
    override suspend fun read(key: String): ByteArray? =
        withRetained(service, validKey(key)) { values ->
            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(
                dictionary(
                    base(values[0], values[1]) + mapOf(
                        kSecReturnData to kCFBooleanTrue,
                        kSecMatchLimit to kSecMatchLimitOne,
                    ),
                ),
                result.ptr,
            )
            when (status) {
                errSecItemNotFound -> null
                errSecSuccess -> {
                    val data = CFBridgingRelease(result.value) as? NSData
                        ?: keychainFailure("read returned non-data", status)
                    data.bytes?.readBytes(data.length.toInt()) ?: ByteArray(0)
                }
                else -> keychainFailure("read", status)
            }
        }

    override suspend fun write(key: String, value: ByteArray) {
        require(value.isNotEmpty()) { "secure values must not be empty" }
        val data = value.toNSData()
        withRetained(service, validKey(key), data) { values ->
            val base = base(values[0], values[1])
            val status = SecItemAdd(
                dictionary(
                    base + mapOf(
                        kSecValueData to values[2],
                        kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
                    ),
                ),
                null,
            )
            if (status == errSecDuplicateItem) {
                val updateStatus = SecItemUpdate(
                    dictionary(base),
                    dictionary(mapOf(kSecValueData to values[2])),
                )
                if (updateStatus != errSecSuccess) keychainFailure("update", updateStatus)
            } else if (status != errSecSuccess) {
                keychainFailure("write", status)
            }
        }
    }

    override suspend fun delete(key: String) {
        withRetained(service, validKey(key)) { values ->
            val status = SecItemDelete(dictionary(base(values[0], values[1])))
            if (status != errSecSuccess && status != errSecItemNotFound) {
                keychainFailure("delete", status)
            }
        }
    }

    private fun base(service: CFTypeRef?, key: CFTypeRef?) = mapOf(
        kSecClass to kSecClassGenericPassword,
        kSecAttrService to service,
        kSecAttrAccount to key,
    )

    private fun validKey(key: String): String {
        require(key.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "invalid secure-storage key" }
        return key
    }

    private fun keychainFailure(operation: String, status: Int): Nothing =
        throw PlatformServiceUnavailableException("iOS Keychain $operation (OSStatus $status)")
}

private fun MemScope.dictionary(items: Map<CFStringRef?, CFTypeRef?>): CFDictionaryRef? {
    val keys = allocArrayOf(*items.keys.toTypedArray())
    val values = allocArrayOf(*items.values.toTypedArray())
    return CFDictionaryCreate(
        kCFAllocatorDefault,
        keys.reinterpret(),
        values.reinterpret(),
        items.size.convert(),
        null,
        null,
    )
}

private inline fun <T> withRetained(
    vararg values: Any?,
    block: MemScope.(Array<CFTypeRef?>) -> T,
): T = memScoped {
    val retained = Array(values.size) { index -> CFBridgingRetain(values[index]) }
    try {
        block(retained)
    } finally {
        retained.forEach { CFBridgingRelease(it) }
    }
}

internal fun ByteArray.toNSData(): NSData = usePinned {
    NSData.create(bytes = it.addressOf(0), length = size.toULong())
}
