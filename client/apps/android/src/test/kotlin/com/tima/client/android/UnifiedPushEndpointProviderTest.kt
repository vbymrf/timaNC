package com.tima.client.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UnifiedPushEndpointProviderTest {
    @Test
    fun acceptsDistributorIssuedHttpsEndpoint() {
        assertEquals(
            "https://push.example.test/message/device-token",
            validatedUnifiedPushEndpoint(" https://push.example.test/message/device-token "),
        )
    }

    @Test
    fun rejectsInsecureOrCredentialBearingEndpoint() {
        assertFailsWith<IllegalArgumentException> {
            validatedUnifiedPushEndpoint("http://push.example.test/device-token")
        }
        assertFailsWith<IllegalArgumentException> {
            validatedUnifiedPushEndpoint("https://user:password@push.example.test/device-token")
        }
    }
}
