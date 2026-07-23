@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.io.encoding.ExperimentalEncodingApi::class,
)

package com.tima.client.ios

import com.tima.client.network.AttestationProof
import com.tima.client.network.AttestationProvider
import com.tima.client.network.PlatformServiceUnavailableException
import kotlinx.cinterop.readBytes
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import platform.DeviceCheck.DCAppAttestService
import platform.Foundation.NSData
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.io.encoding.Base64

data class AppAttestEnrollment(
    val keyId: String,
    val attestationObject: ByteArray,
)

/**
 * App Attest has a distinct one-time key enrollment and recurring assertion
 * lifecycle. The current Phase 1 API schema accepts assertions but has no key
 * enrollment endpoint, so the host must send [AppAttestEnrollment] to its
 * enrollment backend and call [markEnrolled] before assertions are enabled.
 */
class AppAttestProvider(
    private val keychain: KeychainSecureStorage,
) : AttestationProvider {
    private val service = DCAppAttestService.sharedService

    suspend fun prepareEnrollment(serverChallengeSha256: ByteArray): AppAttestEnrollment {
        require(serverChallengeSha256.size == 32)
        requireSupported()
        val existingKey = keychain.read(KEY_ID)?.decodeToString()
        val keyId = existingKey ?: generateKey().also {
            keychain.write(KEY_ID, it.encodeToByteArray())
        }
        val existingObject = keychain.read(PENDING_ATTESTATION)
        val attestationObject = existingObject ?: attestKey(
            keyId,
            serverChallengeSha256.toNSData(),
        ).also { keychain.write(PENDING_ATTESTATION, it) }
        return AppAttestEnrollment(keyId, attestationObject.copyOf())
    }

    suspend fun markEnrolled(keyId: String) {
        check(keychain.read(KEY_ID)?.decodeToString() == keyId) {
            "cannot enroll an unknown App Attest key"
        }
        keychain.write(ENROLLED, byteArrayOf(1))
        keychain.delete(PENDING_ATTESTATION)
    }

    override suspend fun attest(action: String, requestBodySha256: ByteArray): AttestationProof {
        require(action.isNotBlank())
        require(requestBodySha256.size == 32)
        requireSupported()
        val keyId = keychain.read(KEY_ID)?.decodeToString()
            ?: throw PlatformServiceUnavailableException("App Attest key enrollment")
        if (keychain.read(ENROLLED) == null) {
            throw PlatformServiceUnavailableException(
                "App Attest key enrollment (host must complete enrollment first)",
            )
        }
        val assertion = generateAssertion(keyId, requestBodySha256.toNSData())
        return AttestationProof(
            endpoint = "/v1/verify/attestation/ios",
            body = buildJsonObject {
                put("key_id", keyId)
                put("assertion", Base64.Default.encode(assertion))
                put("challenge", Base64.Default.encode(requestBodySha256))
            },
        )
    }

    private fun requireSupported() {
        if (!service.supported) {
            throw PlatformServiceUnavailableException("Apple App Attest")
        }
    }

    private suspend fun generateKey(): String =
        suspendCancellableCoroutine { continuation ->
            service.generateKeyWithCompletionHandler { keyId, error ->
                when {
                    error != null -> continuation.resumeWithException(
                        PlatformServiceUnavailableException(
                            "Apple App Attest key generation: ${error.localizedDescription}",
                        ),
                    )
                    keyId.isNullOrBlank() -> continuation.resumeWithException(
                        PlatformServiceUnavailableException("Apple App Attest returned no key"),
                    )
                    else -> continuation.resume(keyId)
                }
            }
        }

    private suspend fun attestKey(keyId: String, hash: NSData): ByteArray =
        suspendCancellableCoroutine { continuation ->
            service.attestKey(keyId, clientDataHash = hash) { value, error ->
                continuation.resumeDataOrError("key attestation", value, error?.localizedDescription)
            }
        }

    private suspend fun generateAssertion(keyId: String, hash: NSData): ByteArray =
        suspendCancellableCoroutine { continuation ->
            service.generateAssertion(keyId, clientDataHash = hash) { value, error ->
                continuation.resumeDataOrError("assertion", value, error?.localizedDescription)
            }
        }

    private companion object {
        const val KEY_ID = "app-attest.key-id"
        const val ENROLLED = "app-attest.enrolled"
        const val PENDING_ATTESTATION = "app-attest.pending-object"
    }
}

private fun kotlinx.coroutines.CancellableContinuation<ByteArray>.resumeDataOrError(
    operation: String,
    value: NSData?,
    error: String?,
) {
    when {
        error != null -> resumeWithException(
            PlatformServiceUnavailableException("Apple App Attest $operation: $error"),
        )
        value == null -> resumeWithException(
            PlatformServiceUnavailableException("Apple App Attest returned no $operation"),
        )
        else -> resume(value.bytes?.readBytes(value.length.toInt()) ?: ByteArray(0))
    }
}
