package com.suzukiscope.core.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PowerLimitDetectorTest {

    private fun pull(airflow: Double, advance: Double = 5.0, pedal: Double = 100.0, target: Double = 100.0, chargeAirC: Double = 25.0) =
        PowerLimitSample(
            timestampMs = 0, rpm = 4100.0, speedKmh = 100.0, gear = 3, pedalPercent = pedal,
            targetThrottlePercent = target, manifoldKpa = 220.0, airflowGps = airflow, advanceDegrees = advance,
            fuelCut = false, chargeAirTempC = chargeAirC, baroKpa = 84.0,
        )

    private fun PowerLimitDetector.feed(values: List<Double>) = values.forEach { observe(pull(it), learn = true) }

    @Test
    fun referenceIsTheThirdBestSoOneGlitchCannotSetIt() {
        val detector = PowerLimitDetector()
        detector.feed(listOf(100.0, 101.0, 102.0, 99.0, 100.0, 98.0, 100.0, 250.0))
        // Third-best of 250, 102, 101 is 101; the 250 glitch alone doesn't set the reference.
        val result = detector.observe(pull(101.0), learn = false)!!
        assertEquals(100.0, result.airflowAvailablePercent!!, 0.5)
    }

    @Test
    fun weakerPullsNeverLowerTheReference() {
        val detector = PowerLimitDetector()
        detector.feed(List(8) { 110.0 })
        detector.feed(List(20) { 90.0 })
        assertEquals(90.0 / 110.0 * 100, detector.observe(pull(90.0), learn = false)!!.airflowAvailablePercent!!, 0.5)
    }

    @Test
    fun noReferenceUntilEnoughSamplesAndNothingLearnedWithoutPermission() {
        val detector = PowerLimitDetector()
        repeat(20) { detector.observe(pull(110.0), learn = false) }
        assertNull(detector.observe(pull(110.0), learn = false)!!.airflowAvailablePercent)
        detector.feed(List(7) { 110.0 })
        assertNull(detector.observe(pull(110.0), learn = false)!!.airflowAvailablePercent)
    }

    @Test
    fun partPedalIsNotLearnedAndThrottleReductionIsReported() {
        val detector = PowerLimitDetector()
        repeat(20) { detector.observe(pull(60.0, pedal = 85.0, target = 50.0), learn = true) }
        assertEquals(0, detector.coverage().airflowCells)
        assertEquals(35.0, detector.observe(pull(60.0, pedal = 100.0, target = 65.0), learn = true)!!.throttleReductionPercent, 0.01)
    }

    @Test
    fun warmerChargeAirIsCorrectedUpwards() {
        val cool = PowerLimitDetector.correctedAirflow(pull(100.0, chargeAirC = 25.0))
        val hot = PowerLimitDetector.correctedAirflow(pull(100.0, chargeAirC = 55.0))
        assertTrue(hot > cool * 1.04 && hot < cool * 1.06)
    }

    @Test
    fun storeKeepsVehiclesApartAndExportsWithoutTheVinSerial() {
        val store = PowerReferenceStore()
        store.detectorFor("JSAAZC33S00117221").feed(List(8) { 110.0 })
        store.detectorFor("JSAAZC33S00403051").feed(List(8) { 90.0 })
        val restored = PowerReferenceStore().apply { loadJson(store.toJson()) }
        val mine = restored.detectorFor("JSAAZC33S00117221").observe(pull(110.0), learn = false)!!
        assertEquals(100.0, mine.airflowAvailablePercent!!, 0.5)

        val export = store.exportJson(0)
        assertTrue("117221" !in export && "403051" !in export)
        assertTrue("JSAAZC33S00" in export)
        assertNotEquals(PowerReferenceStore.anonymousId("JSAAZC33S00117221"), PowerReferenceStore.anonymousId("JSAAZC33S00403051"))
    }

    @Test
    fun overallReductionCombinesAirflowAndTiming() {
        val detector = PowerLimitDetector()
        detector.feed(List(8) { 100.0 })
        // 90% airflow and 4 degrees below best timing: 1 - 0.9 * (1 - 0.0025 * 16) = 13.6%.
        assertEquals(13.6, detector.observe(pull(90.0, advance = 1.0), learn = false)!!.reductionPercent!!, 0.05)
        assertEquals(0.0, detector.observe(pull(40.0, pedal = 50.0, target = 50.0), learn = false)!!.reductionPercent!!, 0.0)
    }

    @Test
    fun spoolUpIsNotCountedAsAReduction() {
        val detector = PowerLimitDetector()
        detector.feed(List(8) { 100.0 })
        detector.observe(pull(60.0).copy(manifoldKpa = 150.0), learn = false)
        assertNull(detector.observe(pull(80.0).copy(manifoldKpa = 185.0), learn = false))
    }

    @Test
    fun longTermIgnoresOccasionalCutsButShowsAConstantShortfall() {
        val detector = PowerLimitDetector()
        detector.feed(List(8) { 100.0 })
        repeat(80) { i -> detector.observe(pull(if (i % 10 == 0) 50.0 else 100.0), learn = false) }
        assertEquals(0.0, detector.observe(pull(100.0), learn = false)!!.longTermReductionPercent!!, 0.5)
        repeat(100) { detector.observe(pull(92.0), learn = false) }
        assertEquals(8.0, detector.observe(pull(92.0), learn = false)!!.longTermReductionPercent!!, 0.5)
    }
}
