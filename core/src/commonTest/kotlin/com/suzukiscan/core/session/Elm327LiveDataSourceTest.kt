package com.suzukiscan.core.session

import com.suzukiscan.core.field.DecodeSpec
import com.suzukiscan.core.field.FieldDefinition
import com.suzukiscan.core.field.RequestSpec
import com.suzukiscan.core.transport.LoopbackTransport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class Elm327LiveDataSourceTest {

    @Test
    fun pollsAndDecodesAKLineFieldOverLoopbackTransport() = runTest {
        // Synthetic example (not a verified real DID) purely to exercise the wire mechanics:
        // 80 10 F1 = KWP2000 header (physical, target 0x10, source 0xF1), 61 15 50 = positive
        // response to mode 0x21 DID 0x15 with one data byte 0x50 (=80 after -40 offset), B7 = checksum.
        val transport = LoopbackTransport { bytes ->
            val cmd = bytes.decodeToString().trim()
            when {
                cmd.startsWith("2115") -> "80 10 F1 61 15 50 B7\r>".encodeToByteArray()
                else -> "OK\r>".encodeToByteArray()
            }
        }
        val source = Elm327LiveDataSource(transport)
        source.connect()

        val oilTemp = FieldDefinition(
            id = "test.field",
            label = "Test Field",
            unit = "\u00B0C",
            request = RequestSpec(targetAddress = 0x10, mode = 0x21, params = listOf(0x15)),
            decode = DecodeSpec(skipBytes = 2, byteLength = 1, offset = -40.0),
        )

        assertEquals(40.0, source.poll(oilTemp))
    }
}
