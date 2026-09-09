package com.suzukiscope.core.dtc

import kotlin.test.Test
import kotlin.test.assertEquals

class DtcTest {

    @Test
    fun formatsPowertrainCode() {
        // 0x01, 0x20 -> P0120 (throttle position sensor circuit, a real, well-known OBD-II code)
        assertEquals("P0120", formatDtc(0x01, 0x20))
    }

    @Test
    fun formatsChassisBodyNetworkPrefixes() {
        assertEquals("C0120", formatDtc(0x41, 0x20))
        assertEquals("B0120", formatDtc(0x81, 0x20))
        assertEquals("U0120", formatDtc(0xC1, 0x20))
    }
}
