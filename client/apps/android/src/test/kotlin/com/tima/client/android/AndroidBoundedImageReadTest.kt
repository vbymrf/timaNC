package com.tima.client.android

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFails

class AndroidBoundedImageReadTest {
    @Test
    fun pickerReadIsStrictlyBounded() {
        assertContentEquals(
            byteArrayOf(1, 2, 3),
            readBounded(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3),
        )
        assertFails { readBounded(ByteArrayInputStream(ByteArray(5)), 4) }
    }
}
