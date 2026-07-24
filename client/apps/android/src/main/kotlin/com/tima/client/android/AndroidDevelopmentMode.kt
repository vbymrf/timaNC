package com.tima.client.android

import android.util.Base64
import com.tima.client.network.AttestationProof
import com.tima.client.network.AttestationProvider
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object DevelopmentModeGate {
    fun enabled(debugBuild: Boolean, explicitDevelopmentAuth: Boolean): Boolean =
        debugBuild && explicitDevelopmentAuth
}

/**
 * Development-server fixture only. Construction is guarded by [DevelopmentModeGate] in the
 * runtime; release BuildConfig hard-codes the explicit flag to false.
 */
class DevelopmentAndroidAttestationProvider private constructor() : AttestationProvider {
    override suspend fun attest(action: String, requestBodySha256: ByteArray): AttestationProof {
        require(action == "register" || action == "login")
        require(requestBodySha256.size == 32)
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(DEVELOPMENT_SECRET, "HmacSHA256"))
            update("android".encodeToByteArray())
            update(byteArrayOf(0))
            update(byteArrayOf(0))
            update(requestBodySha256)
        }
        return AttestationProof(
            endpoint = "/v1/verify/integrity/android",
            body = buildJsonObject {
                put("integrity_token", Base64.encodeToString(mac.doFinal(), Base64.NO_WRAP))
                put("nonce", Base64.encodeToString(requestBodySha256, Base64.NO_WRAP))
            },
        )
    }

    companion object {
        fun create(debugBuild: Boolean, explicitDevelopmentAuth: Boolean): AttestationProvider {
            check(DevelopmentModeGate.enabled(debugBuild, explicitDevelopmentAuth)) {
                "development attestation is disabled"
            }
            return DevelopmentAndroidAttestationProvider()
        }

        fun requestHash(body: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(body.encodeToByteArray())

        private val DEVELOPMENT_SECRET = "development-attestation-key".encodeToByteArray()
    }
}
