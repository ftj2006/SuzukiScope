package com.suzukiscope.core.elm327

import kotlin.test.Test
import kotlin.test.assertEquals

class Elm327ClientTest {

    @Test
    fun cleanResponseStripsPromptAndSearchingNoise() {
        val raw = "SEARCHING...\r41 0C 1A F8\r\r>"
        assertEquals("41 0C 1A F8", Elm327Client.cleanResponse(raw))
    }

    @Test
    fun parseHexBytesHandlesSpacedHex() {
        val bytes = Elm327Client.parseHexBytes("61 15 50")
        assertEquals(listOf<Byte>(0x61, 0x15, 0x50), bytes.toList())
    }

    @Test
    fun parseHexBytesStripsCanLineLabels() {
        val bytes = Elm327Client.parseHexBytes("0D2\r0:6100FF\r1:1234\r2:FFFFFFFFFFFFFFFF\rF:ABCD\r>")
        assertEquals(listOf<Byte>(0x61, 0x00, 0xFF.toByte(), 0x12, 0x34) +
            List(8) { 0xFF.toByte() } + listOf<Byte>(0xAB.toByte(), 0xCD.toByte()), bytes.toList())
    }

    @Test
    fun sendCommandRoundTripsThroughLoopbackTransport() = kotlinx.coroutines.test.runTest {
        val transport = com.suzukiscope.core.transport.LoopbackTransport { bytes ->
            val cmd = bytes.decodeToString().trim()
            when {
                cmd == "ATZ" -> "ELM327 v1.5\r>".encodeToByteArray()
                cmd.startsWith("21") -> "80 10 F1 61 15 50 B7\r>".encodeToByteArray()
                else -> "OK\r>".encodeToByteArray()
            }
        }
        val client = Elm327Client(transport)
        client.connect()
        client.reset()
        val response = client.sendCommand("2115")
        assertEquals("80 10 F1 61 15 50 B7", response)
    }
}
