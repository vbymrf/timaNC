package com.tima.client.android

import android.content.Context
import com.tima.client.network.PlatformServiceUnavailableException
import com.tima.client.network.PushTokenProvider
import java.net.URI

/**
 * Narrow boundary for a UnifiedPush distributor integration. The host passes
 * distributor-issued endpoints here; this client neither chooses a distributor
 * nor synthesizes an endpoint when none is installed.
 */
class UnifiedPushEndpointProvider(context: Context) : PushTokenProvider {
    private val preferences = context.applicationContext.getSharedPreferences(
        "tima-unifiedpush",
        Context.MODE_PRIVATE,
    )

    override val provider: String = "unifiedpush"

    fun acceptDistributorEndpoint(endpoint: String) {
        val normalized = validatedUnifiedPushEndpoint(endpoint)
        preferences.edit().putString(ENDPOINT, normalized).apply()
    }

    fun clearDistributorEndpoint() {
        preferences.edit().remove(ENDPOINT).apply()
    }

    override suspend fun currentToken(): String =
        preferences.getString(ENDPOINT, null)
            ?: throw PlatformServiceUnavailableException(
                "UnifiedPush endpoint (no distributor endpoint has been supplied)",
            )

    private companion object {
        const val ENDPOINT = "endpoint"
    }
}

internal fun validatedUnifiedPushEndpoint(endpoint: String): String {
    val normalized = endpoint.trim()
    val uri = runCatching { URI(normalized) }.getOrNull()
    require(
        uri?.scheme == "https" &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.fragment == null &&
            normalized.length in 16..4096,
    ) {
        "UnifiedPush distributor endpoint must be a credential-free HTTPS URL"
    }
    return normalized
}
