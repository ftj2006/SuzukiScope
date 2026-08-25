package com.suzukiscan.core.dtc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DtcDescriptionsTest {

    @Test
    fun loadsAlmostAllExtractedCodes() {
        assertTrue(DtcDescriptions.all.size > 1000, "expected ~1449 codes, got ${DtcDescriptions.all.size}")
    }

    @Test
    fun describesAKnownCode() {
        assertEquals(
            "Camshaft position actuator circuit; \"A\" Camshaft Position Actuator Circuit / Open",
            DtcDescriptions.describe("P0010"),
        )
    }

    @Test
    fun unknownCodeReturnsNull() {
        assertEquals(null, DtcDescriptions.describe("Z9999"))
    }
}
