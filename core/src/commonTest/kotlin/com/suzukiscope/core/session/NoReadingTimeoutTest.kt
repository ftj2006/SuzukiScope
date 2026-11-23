package com.suzukiscope.core.session

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NoReadingTimeoutTest {
    @Test
    fun expiresAfterFiveMinutesDespiteReconnects() {
        val timeout = NoReadingTimeout(300_000)
        assertFalse(timeout.observe(12_000, 1, 12_000, false))
        assertFalse(timeout.observe(120_000, 1, null, false))
        assertFalse(timeout.observe(299_999, 1, null, false))
        assertTrue(timeout.observe(300_000, 1, null, false))
    }

    @Test
    fun actualReadingsAndDiagnosticsResetTheTimer() {
        val timeout = NoReadingTimeout(300_000)
        assertFalse(timeout.observe(12_000, 1, 12_000, false))
        assertFalse(timeout.observe(299_000, 298_000, null, false))
        assertFalse(timeout.observe(310_000, 298_000, 12_000, false))
        assertFalse(timeout.observe(500_000, 298_000, null, true))
        assertFalse(timeout.observe(800_000, 298_000, null, false))
        assertFalse(timeout.observe(812_000, 800_000, 12_000, false))
        assertTrue(timeout.observe(1_100_000, 800_000, null, false))
    }
}