package com.suzukiscan.core.log

import kotlin.test.Test
import kotlin.test.assertEquals

class LiveDataRecorderTest {

    @Test
    fun tracksPeakAndMinAcrossReadings() {
        val recorder = LiveDataRecorder()
        recorder.record(Reading("engine.oil_temp", 1L, 80.0))
        recorder.record(Reading("engine.oil_temp", 2L, 95.0))
        recorder.record(Reading("engine.oil_temp", 3L, 60.0))

        assertEquals(95.0, recorder.peak("engine.oil_temp"))
        assertEquals(60.0, recorder.min("engine.oil_temp"))
    }

    @Test
    fun exportsCsvWithHeaderAndRows() {
        val recorder = LiveDataRecorder()
        recorder.record(Reading("engine.oil_temp", 1000L, 80.0))
        val csv = recorder.toCsv(labelFor = { "Oil Temperature" }, unitFor = { "\u00B0C" })
        val lines = csv.trim().lines()
        assertEquals("timestamp_ms,field_id,label,value,unit", lines[0])
        assertEquals("1000,engine.oil_temp,Oil Temperature,80.0,\u00B0C", lines[1])
    }
}
