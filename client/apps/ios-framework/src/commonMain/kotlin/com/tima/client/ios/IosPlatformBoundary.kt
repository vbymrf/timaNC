package com.tima.client.ios

data class IosAttestationProof(
    val keyId: String,
    val assertion: ByteArray,
    val challenge: ByteArray,
)

/**
 * Implemented by the Swift host because App Attest and APNs are lifecycle-driven OS APIs.
 */
interface IosPlatformHost {
    fun attest(
        action: String,
        requestBodySha256: ByteArray,
        completion: (IosAttestationProof?, String?) -> Unit,
    )

    fun currentApnsToken(completion: (String?, String?) -> Unit)
}

class IosPlatformBoundary(private val host: IosPlatformHost) {
    fun attest(
        action: String,
        requestBodySha256: ByteArray,
        completion: (IosAttestationProof?, String?) -> Unit,
    ) {
        require(action.isNotBlank())
        require(requestBodySha256.size == 32)
        host.attest(action, requestBodySha256.copyOf()) { proof, error ->
            if (proof == null || proof.keyId.isBlank() || proof.assertion.isEmpty() ||
                !proof.challenge.contentEquals(requestBodySha256)
            ) {
                completion(null, error ?: "App Attest returned an invalid proof")
            } else {
                completion(proof, null)
            }
        }
    }

    fun currentApnsToken(completion: (String?, String?) -> Unit) {
        host.currentApnsToken { token, error ->
            if (token.isNullOrBlank()) {
                completion(null, error ?: "APNs token is unavailable")
            } else {
                completion(token, null)
            }
        }
    }
}
