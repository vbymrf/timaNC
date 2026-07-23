@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.tima.client.ios

import com.tima.client.network.PlatformServiceUnavailableException
import com.tima.client.network.PushTokenProvider
import kotlinx.cinterop.readBytes
import platform.Foundation.NSData

/**
 * APNs issues tokens only through UIApplicationDelegate. The Swift host must
 * pass didRegisterForRemoteNotificationsWithDeviceToken here; this adapter
 * never invents or substitutes a token.
 */
class ApnsPushTokenProvider(
    private val keychain: KeychainSecureStorage,
) : PushTokenProvider {
    override val provider: String = "apns"

    suspend fun didRegisterForRemoteNotifications(deviceToken: NSData) {
        val bytes = deviceToken.bytes?.readBytes(deviceToken.length.toInt()) ?: ByteArray(0)
        require(bytes.size >= 16) { "APNs returned an invalid device token" }
        keychain.write(TOKEN, bytes)
    }

    suspend fun didFailToRegisterForRemoteNotifications() {
        keychain.delete(TOKEN)
    }

    override suspend fun currentToken(): String {
        val bytes = keychain.read(TOKEN)
            ?: throw PlatformServiceUnavailableException(
                "APNs token (UIApplicationDelegate has not supplied one)",
            )
        return bytes.joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private companion object {
        const val TOKEN = "push.apns-token"
    }
}
