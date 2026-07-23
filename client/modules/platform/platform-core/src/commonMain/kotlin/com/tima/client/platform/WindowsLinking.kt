package com.tima.client.platform

data class WindowsLinkKeys(
    val identityPublicKey: ByteArray,
    val signingPublicKey: ByteArray,
)

data class WindowsLinkChallenge(
    val sessionId: String,
    val qrPayload: String,
    val claimToken: String,
)

data class WindowsLinkedSession(
    val sessionId: String,
    val accessToken: String,
    val wrappedDeviceSecret: ByteArray,
)

interface WindowsLinkGateway {
    suspend fun start(desktopName: String, keys: WindowsLinkKeys): WindowsLinkChallenge
    suspend fun claim(sessionId: String, claimToken: String): WindowsLinkedSession
}

data class WindowsQrLink(val sessionId: String, val secret: String) {
    companion object {
        private val pattern = Regex(
            """^tima://link/v1\?session_id=([0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})&secret=([A-Za-z0-9_-]{43,})$""",
        )

        fun parse(payload: String): WindowsQrLink {
            val match = pattern.matchEntire(payload)
                ?: throw IllegalArgumentException("invalid Windows link QR payload")
            return WindowsQrLink(match.groupValues[1], match.groupValues[2])
        }
    }
}

/**
 * Claim tokens are persisted only through the OS-protected store and consumed exactly once.
 */
class WindowsLinkCoordinator(
    private val gateway: WindowsLinkGateway,
    private val secrets: SecureSecretStore,
) {
    suspend fun start(desktopName: String, keys: WindowsLinkKeys): WindowsLinkChallenge {
        require(desktopName.isNotBlank() && desktopName.length <= 100)
        require(keys.identityPublicKey.size == 32)
        require(keys.signingPublicKey.size == 32)
        val challenge = gateway.start(desktopName, keys)
        val qr = WindowsQrLink.parse(challenge.qrPayload)
        check(qr.sessionId == challenge.sessionId) { "link service returned mismatched session" }
        check(challenge.claimToken.isNotBlank()) { "link service omitted claim token" }
        secrets.put(claimAlias(challenge.sessionId), challenge.claimToken.encodeToByteArray())
        return challenge.copy(claimToken = "")
    }

    suspend fun claim(sessionId: String): WindowsLinkedSession {
        require(sessionId.matches(UUID))
        val alias = claimAlias(sessionId)
        val tokenBytes = secrets.get(alias)
            ?: throw PlatformServiceUnavailable("Windows link claim is not available")
        val token = tokenBytes.decodeToString()
        tokenBytes.fill(0)
        check(token.isNotBlank()) { "stored Windows link claim is empty" }
        val linked = gateway.claim(sessionId, token)
        check(linked.sessionId == sessionId) { "link service returned mismatched claim" }
        check(linked.accessToken.isNotBlank() && linked.wrappedDeviceSecret.isNotEmpty())
        secrets.delete(alias)
        return linked
    }

    private fun claimAlias(sessionId: String) = "windows-link-claim-$sessionId"

    private companion object {
        val UUID = Regex(
            """^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$""",
        )
    }
}
