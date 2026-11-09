package com.suzukiscope.core.session

import com.suzukiscope.core.elm327.Elm327Protocol
import com.suzukiscope.core.field.DecodeSpec
import com.suzukiscope.core.field.FieldDefinition
import com.suzukiscope.core.field.FieldFallback
import com.suzukiscope.core.field.OBD_MODE_CURRENT_DATA
import com.suzukiscope.core.field.RequestSpec
import com.suzukiscope.core.transport.LoopbackTransport
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

    private fun canAdapter(requests: MutableList<String>) = LoopbackTransport { bytes ->
        val cmd = bytes.decodeToString().trim()
        requests += cmd
        when (cmd) {
            // Supports 0B 0C 0D 11 and the 21-40 range; 0120 then adds 33.
            "0100" -> "41 00 00 38 80 01\r>"
            "0120" -> "41 20 00 00 20 00\r>"
            "010C0B33" -> "41 0C 1A F8 0B 64 33 54\r>"
            "2100" -> "61 00 2A\r>"
            else -> "OK\r>"
        }.encodeToByteArray()
    }

    private fun obdField(id: String, pids: List<Int>, decode: DecodeSpec, fallback: FieldFallback? = null) = FieldDefinition(
        id = id,
        label = id,
        unit = "",
        request = RequestSpec(targetAddress = 0x7E0, mode = OBD_MODE_CURRENT_DATA, params = pids, responsePrefixBytes = 0, responseSuffixBytes = 0),
        decode = decode,
        fallback = fallback,
    )

    @Test
    fun batchesObdPidsOfACycleIntoOneRequest() = runTest {
        val requests = mutableListOf<String>()
        val source = Elm327LiveDataSource(canAdapter(requests), Elm327Protocol.CAN_11BIT_500K)
        source.connect()
        val rpm = obdField("rpm", listOf(0x0C), DecodeSpec(skipBytes = 2, byteLength = 2, scale = 0.25))
        val relativeBoost = obdField("boost", listOf(0x0B, 0x33), DecodeSpec(skipBytes = 2, byteLength = 1, scale = 0.01, subtractSkipBytes = 4))

        source.beginCycle(listOf(rpm, relativeBoost))
        assertEquals(1726.0, source.poll(rpm))
        assertEquals(0.16, source.poll(relativeBoost), 1e-9)
        assertEquals(1, requests.count { it.startsWith("01") && it !in setOf("0100", "0120") })
    }

    @Test
    fun usesFallbackWhenPidUnsupported() = runTest {
        val source = Elm327LiveDataSource(canAdapter(mutableListOf()), Elm327Protocol.CAN_11BIT_500K)
        source.connect()
        val fallback = FieldFallback(
            RequestSpec(targetAddress = 0x7E0, mode = 0x21, params = listOf(0x00), responsePrefixBytes = 0, responseSuffixBytes = 0),
            DecodeSpec(skipBytes = 2, byteLength = 1),
        )
        // PID 0E is not in the supported set above, so the Suzuki request is used instead.
        val timing = obdField("timing", listOf(0x0E), DecodeSpec(skipBytes = 2, byteLength = 1), fallback)

        source.beginCycle(listOf(timing))
        assertEquals(42.0, source.poll(timing))
    }
}
