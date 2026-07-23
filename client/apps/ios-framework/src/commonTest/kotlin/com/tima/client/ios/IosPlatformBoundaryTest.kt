package com.tima.client.ios

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IosPlatformBoundaryTest {
    @Test
    fun rejectsProofNotBoundToRequestHash() {
        val host = object : IosPlatformHost {
            override fun attest(
                action: String,
                requestBodySha256: ByteArray,
                completion: (IosAttestationProof?, String?) -> Unit,
            ) {
                completion(IosAttestationProof("key", byteArrayOf(1), ByteArray(32) { 2 }), null)
            }

            override fun currentApnsToken(completion: (String?, String?) -> Unit) {
                completion(null, "not registered")
            }
        }

        var proof: IosAttestationProof? = IosAttestationProof("unexpected", byteArrayOf(1), ByteArray(32))
        var error: String? = null
        IosPlatformBoundary(host).attest("register", ByteArray(32)) { value, failure ->
            proof = value
            error = failure
        }
        assertNull(proof)
        assertEquals("App Attest returned an invalid proof", error)
    }
}
