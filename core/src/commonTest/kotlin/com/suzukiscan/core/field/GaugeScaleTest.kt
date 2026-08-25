package com.suzukiscan.core.field

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GaugeScaleTest {
    @Test
    fun roundsUpToNiceStepForItsMagnitude() {
        assertEquals(6500.0, niceScaleMax(6420.0))
        assertTrue(kotlin.math.abs(niceScaleMax(1.55) - 1.6) < 1e-9)
        assertEquals(110.0, niceScaleMax(105.0))
        assertEquals(1000.0, niceScaleMax(999.0))
    }

    @Test
    fun leavesExactMultiplesUnchanged() {
        assertEquals(6400.0, niceScaleMax(6400.0))
        assertEquals(100.0, niceScaleMax(100.0))
    }
}

