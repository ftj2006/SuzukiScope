package com.suzukiscope.core.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.max
import kotlin.math.sqrt

/** Decoded channels from one Suzuki engine `21 00` reply. */
data class PowerLimitSample(
    val timestampMs: Long,
    val rpm: Double,
    val speedKmh: Double,
    val gear: Int,
    val pedalPercent: Double,
    val targetThrottlePercent: Double,
    val manifoldKpa: Double,
    val airflowGps: Double,
    val advanceDegrees: Double,
    val fuelCut: Boolean,
    val chargeAirTempC: Double,
    val baroKpa: Double,
)

data class PowerLimitValues(
    /** Estimated share of this car's learned full power that is missing right now; 0 when the driver isn't asking for it. */
    val reductionPercent: Double?,
    val longTermReductionPercent: Double?,
    val airflowAvailablePercent: Double? = null,
    val throttleReductionPercent: Double = 0.0,
    val timingDeficitDegrees: Double? = null,
    val longTermTimingDeficitDegrees: Double? = null,
    val referenceChanged: Boolean,
)

data class PowerReferenceCoverage(val airflowCells: Int, val timingCells: Int)

/**
 * Learns this vehicle's best full-pedal airflow, timing and boost per operating cell, continuously and only upwards.
 * The reference is the third-best value once a cell has enough samples, so one glitch can't set it.
 * Airflow is a relative power proxy, not measured power; the cause of a reduction is never inferred here.
 */
class PowerLimitDetector {
    @Serializable
    data class Cell(
        val top: List<Double> = emptyList(),
        val sampleCount: Int = 0,
        val chargeAirTempCAtBest: Double? = null,
        val baroKpaAtBest: Double? = null,
    ) {
        val reference: Double? get() = top.getOrNull(TOP_VALUES - 1)?.takeIf { sampleCount >= MIN_REFERENCE_SAMPLES }
    }

    @Serializable
    data class Snapshot(
        val airflowCells: Map<String, Cell> = emptyMap(),
        val timingCells: Map<String, Cell> = emptyMap(),
        val boostCells: Map<String, Cell> = emptyMap(),
        val recentReductions: List<Double> = emptyList(),
        val recentTimingDeficits: List<Double> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val airflowCells = HashMap<String, Cell>()
    private val timingCells = HashMap<String, Cell>()
    private val boostCells = HashMap<String, Cell>()
    private val recentReductions = ArrayDeque<Double>()
    private val recentTimingDeficits = ArrayDeque<Double>()
    private var previousManifoldKpa: Double? = null

    fun coverage() = PowerReferenceCoverage(
        airflowCells.values.count { it.reference != null },
        timingCells.values.count { it.reference != null },
    )

    fun reset() {
        airflowCells.clear()
        timingCells.clear()
        boostCells.clear()
        recentReductions.clear()
        recentTimingDeficits.clear()
    }

    fun observe(sample: PowerLimitSample, learn: Boolean): PowerLimitValues? {
        val manifoldRise = previousManifoldKpa?.let { sample.manifoldKpa - it } ?: 0.0
        previousManifoldKpa = sample.manifoldKpa
        val longTerm = longTermMedian(recentReductions)
        val longTiming = longTermMedian(recentTimingDeficits)
        val throttleReduction =
            if (sample.pedalPercent >= 80.0) (sample.pedalPercent - sample.targetThrottlePercent).coerceAtLeast(0.0) else 0.0
        if (sample.pedalPercent < DEMAND_PEDAL) {
            return PowerLimitValues(0.0, longTerm, throttleReductionPercent = throttleReduction, longTermTimingDeficitDegrees = longTiming, referenceChanged = false)
        }
        if (sample.gear !in 2..6 || sample.rpm !in 1800.0..6500.0 || sample.speedKmh < 10) return null

        val fullPedal = sample.pedalPercent >= 95.0 && sample.targetThrottlePercent >= 95.0 && !sample.fuelCut
        val boostEstablished = sample.manifoldKpa >= MIN_BOOST_KPA
        val airflow = correctedAirflow(sample)
        val airflowKey = airflowCell(sample)
        val timingKey = timingCell(sample)

        var changed = false
        if (learn && fullPedal && boostEstablished && airflow > 0.0) {
            changed = record(airflowCells, airflowKey, airflow, sample) or
                record(timingCells, timingKey, sample.advanceDegrees, sample) or
                record(boostCells, airflowKey, sample.manifoldKpa, sample)
        }

        // Turbo still spooling with the throttle open: lag, not a limit.
        if (fullPedal && manifoldRise >= SPOOL_RISE_KPA) return null

        val airRatio = airflowCells[airflowKey]?.reference?.let { (airflow / it).coerceIn(0.0, 1.0) }
        val timingDeficit = timingCells[timingKey]?.reference
            ?.let { max(0.0, it - sample.advanceDegrees) }
            ?.takeIf { boostEstablished && !sample.fuelCut }
        if (airRatio == null && timingDeficit == null) {
            return PowerLimitValues(null, longTerm, throttleReductionPercent = throttleReduction, longTermTimingDeficitDegrees = longTiming, referenceChanged = changed)
        }
        val sparkEfficiency = (1.0 - SPARK_LOSS_PER_DEG2 * (timingDeficit ?: 0.0).let { it * it }).coerceAtLeast(0.0)
        val reduction = 100.0 * (1.0 - (airRatio ?: 1.0) * sparkEfficiency)

        // Only settled full-pedal readings say what the car can deliver; part pedal and throttle cuts are short-term.
        if (fullPedal && boostEstablished) {
            remember(recentReductions, reduction)
            timingDeficit?.let { remember(recentTimingDeficits, it) }
        }

        return PowerLimitValues(
            reductionPercent = reduction,
            longTermReductionPercent = longTermMedian(recentReductions),
            airflowAvailablePercent = airRatio?.let { 100.0 * it },
            throttleReductionPercent = throttleReduction,
            timingDeficitDegrees = timingDeficit,
            longTermTimingDeficitDegrees = longTermMedian(recentTimingDeficits),
            referenceChanged = true,
        )
    }

    fun snapshot() = Snapshot(
        airflowCells.toMap(), timingCells.toMap(), boostCells.toMap(),
        recentReductions = recentReductions.toList(), recentTimingDeficits = recentTimingDeficits.toList(),
    )

    fun toJson(): String = json.encodeToString(Snapshot.serializer(), snapshot())

    fun loadJson(text: String) = load(json.decodeFromString(Snapshot.serializer(), text))

    fun load(snapshot: Snapshot) {
        reset()
        airflowCells.putAll(snapshot.airflowCells)
        timingCells.putAll(snapshot.timingCells)
        boostCells.putAll(snapshot.boostCells)
        snapshot.recentReductions.takeLast(LONG_TERM_SAMPLES).forEach(recentReductions::addLast)
        snapshot.recentTimingDeficits.takeLast(LONG_TERM_SAMPLES).forEach(recentTimingDeficits::addLast)
    }

    private fun remember(buffer: ArrayDeque<Double>, value: Double) {
        buffer.addLast(value)
        while (buffer.size > LONG_TERM_SAMPLES) buffer.removeFirst()
    }

    private fun longTermMedian(buffer: ArrayDeque<Double>): Double? =
        buffer.takeIf { it.size >= MIN_LONG_TERM_SAMPLES }?.sorted()?.let { it[it.size / 2] }

    private fun record(cells: MutableMap<String, Cell>, key: String, value: Double, sample: PowerLimitSample): Boolean {
        val previous = cells[key] ?: Cell()
        val top = (previous.top + value).sortedDescending().take(TOP_VALUES)
        val newBest = previous.top.isEmpty() || value > previous.top.first()
        cells[key] = previous.copy(
            top = top,
            sampleCount = previous.sampleCount + 1,
            chargeAirTempCAtBest = if (newBest) sample.chargeAirTempC else previous.chargeAirTempCAtBest,
            baroKpaAtBest = if (newBest) sample.baroKpa else previous.baroKpaAtBest,
        )
        return true
    }

    companion object {
        const val TOP_VALUES = 3
        const val MIN_REFERENCE_SAMPLES = 8
        const val MIN_LONG_TERM_SAMPLES = 50
        // Median of the last 100 settled full-pedal readings (~20-30 s of full throttle, kept across drives).
        const val LONG_TERM_SAMPLES = 100
        const val MIN_BOOST_KPA = 180.0
        const val DEMAND_PEDAL = 90.0
        const val SPOOL_RISE_KPA = 8.0
        // Generic ignition-efficiency approximation (~2% at 3°, ~9% at 6° retard), not a Suzuki figure.
        const val SPARK_LOSS_PER_DEG2 = 0.0025
        const val AIRFLOW_RPM_BIN = 500
        const val TIMING_RPM_BIN = 250
        const val TIMING_MAP_BIN = 10
        const val REFERENCE_CHARGE_AIR_K = 298.15

        // Weekend logs: at fixed boost, full-pedal airflow fell ~0.16%/°C of charge air, matching a square-root law.
        fun correctedAirflow(sample: PowerLimitSample): Double =
            sample.airflowGps * sqrt((sample.chargeAirTempC + 273.15) / REFERENCE_CHARGE_AIR_K)

        fun airflowCell(sample: PowerLimitSample) = "${sample.gear}/${sample.rpm.toInt() / AIRFLOW_RPM_BIN * AIRFLOW_RPM_BIN}"

        fun timingCell(sample: PowerLimitSample) =
            "${sample.gear}/${sample.rpm.toInt() / TIMING_RPM_BIN * TIMING_RPM_BIN}/${sample.manifoldKpa.toInt() / TIMING_MAP_BIN * TIMING_MAP_BIN}"
    }
}
