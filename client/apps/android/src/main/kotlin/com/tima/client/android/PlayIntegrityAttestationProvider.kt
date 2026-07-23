package com.tima.client.android

import android.content.Context
import android.util.Base64
import com.google.android.gms.tasks.Task
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import com.tima.client.network.AttestationProof
import com.tima.client.network.AttestationProvider
import com.tima.client.network.PlatformServiceUnavailableException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Uses Play Integrity Standard requests. The Cloud project number must belong
 * to the Play Console-linked project; a missing or unavailable Play service is
 * an error and never falls back to a locally generated proof.
 */
class PlayIntegrityAttestationProvider(
    context: Context,
    private val cloudProjectNumber: Long,
) : AttestationProvider {
    private val manager = IntegrityManagerFactory.createStandard(context.applicationContext)
    private val preparationMutex = Mutex()
    private var tokenProvider: StandardIntegrityManager.StandardIntegrityTokenProvider? = null

    init {
        require(cloudProjectNumber > 0) { "a Play Integrity Cloud project number is required" }
    }

    suspend fun prepare() {
        provider()
    }

    override suspend fun attest(action: String, requestBodySha256: ByteArray): AttestationProof {
        require(action.isNotBlank())
        require(requestBodySha256.size == 32)
        val requestHash = Base64.encodeToString(
            requestBodySha256,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        val token = runCatching {
            provider().request(
                StandardIntegrityManager.StandardIntegrityTokenRequest.builder()
                    .setRequestHash(requestHash)
                    .build(),
            ).await().token()
        }.getOrElse {
            throw PlatformServiceUnavailableException("Google Play Integrity", it)
        }
        check(token.isNotBlank()) { "Play Integrity returned an empty token" }
        return AttestationProof(
            endpoint = "/v1/verify/integrity/android",
            body = buildJsonObject {
                put("integrity_token", token)
                put("nonce", Base64.encodeToString(requestBodySha256, Base64.NO_WRAP))
            },
        )
    }

    private suspend fun provider(): StandardIntegrityManager.StandardIntegrityTokenProvider =
        tokenProvider ?: preparationMutex.withLock {
            tokenProvider ?: runCatching {
                manager.prepareIntegrityToken(
                    StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
                        .setCloudProjectNumber(cloudProjectNumber)
                        .build(),
                ).await()
            }.getOrElse {
                throw PlatformServiceUnavailableException("Google Play Integrity", it)
            }.also { tokenProvider = it }
        }
}

private suspend fun <T> Task<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { value ->
            if (continuation.isActive) continuation.resume(value)
        }
        addOnFailureListener { error ->
            if (continuation.isActive) continuation.resumeWithException(error)
        }
        addOnCanceledListener { continuation.cancel() }
    }
