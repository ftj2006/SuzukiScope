package com.suzukiscope.core.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Each vehicle's learned power reference, keyed by VIN and kept on the phone only. */
class PowerReferenceStore {
    @Serializable
    private data class Saved(val vehicles: Map<String, PowerLimitDetector.Snapshot> = emptyMap())

    @Serializable
    data class ExportedVehicle(
        val vehicleId: String,
        /** Model/engine part of the VIN; the serial is left out. */
        val vinPrefix: String,
        val reference: PowerLimitDetector.Snapshot,
    )

    @Serializable
    data class Export(
        val format: String = FORMAT,
        val exportedAtMs: Long,
        val airflowCell: String = "gear/rpm in ${PowerLimitDetector.AIRFLOW_RPM_BIN} rpm steps; also used for boost",
        val timingCell: String = "gear/rpm in ${PowerLimitDetector.TIMING_RPM_BIN} rpm steps/MAP in ${PowerLimitDetector.TIMING_MAP_BIN} kPa steps",
        val airflowCorrection: String = "g/s x sqrt((charge-air temp C + 273.15) / 298.15); no baro or humidity correction",
        val learningRule: String = "held full pedal, target throttle >= 95%, MAP >= ${PowerLimitDetector.MIN_BOOST_KPA.toInt()} kPa, no fuel cut; " +
            "reference = 3rd-best of >= ${PowerLimitDetector.MIN_REFERENCE_SAMPLES} samples",
        val reduction: String = "100 x (1 - airflow/reference x (1 - ${PowerLimitDetector.SPARK_LOSS_PER_DEG2} x timing deficit^2)); " +
            "long-term = median of the last ${PowerLimitDetector.LONG_TERM_SAMPLES} settled full-pedal readings",
        val vehicles: List<ExportedVehicle>,
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val detectors = LinkedHashMap<String, PowerLimitDetector>()

    fun detectorFor(vin: String): PowerLimitDetector = detectors.getOrPut(vin) { PowerLimitDetector() }

    fun vehicleCount(): Int = detectors.size

    fun reset(vin: String) { detectors[vin]?.reset() }

    fun toJson(): String = json.encodeToString(Saved.serializer(), Saved(detectors.mapValues { it.value.snapshot() }))

    fun loadJson(text: String) {
        val saved = json.decodeFromString(Saved.serializer(), text)
        detectors.clear()
        saved.vehicles.forEach { (vin, snapshot) -> detectors[vin] = PowerLimitDetector().also { it.load(snapshot) } }
    }

    fun exportJson(nowMs: Long): String = json.encodeToString(
        Export.serializer(),
        Export(
            exportedAtMs = nowMs,
            vehicles = detectors.map { (vin, detector) -> ExportedVehicle(anonymousId(vin), vin.take(11), detector.snapshot()) },
        ),
    )

    companion object {
        const val FORMAT = "suzukiscope-power-reference-v1"

        /** Stable per-VIN id (FNV-1a 64-bit) so exports from several phones can be merged without the full VIN. */
        fun anonymousId(vin: String): String {
            var hash = -0x340d631b7bdddcdbL
            for (c in vin) {
                hash = hash xor c.code.toLong()
                hash *= 0x100000001b3L
            }
            return hash.toULong().toString(16).padStart(16, '0')
        }
    }
}
