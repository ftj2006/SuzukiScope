package com.suzukiscope.core.field

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/**
 * Holds the set of user-configurable [FieldDefinition]s (loaded from/saved to JSON),
 * so fields/gauges shown in the UI are never hardcoded in app code.
 */
class FieldRegistry(initial: List<FieldDefinition> = emptyList()) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val _fields = LinkedHashMap<String, FieldDefinition>()
    val fields: List<FieldDefinition> get() = _fields.values.toList()

    // Each field's built-in scale (code default or catalog), kept separately because learning overwrites gaugeMin/gaugeMax.
    private val defaultRanges = HashMap<String, Pair<Double, Double>>()
    fun defaultRange(id: String): Pair<Double, Double>? = defaultRanges[id]

    init {
        initial.forEach {
            _fields[it.id] = it
            defaultRanges[it.id] = it.gaugeMin to it.gaugeMax
        }
    }

    fun add(field: FieldDefinition) { _fields[field.id] = field }
    fun remove(id: String) { _fields.remove(id) }
    fun setEnabled(id: String, enabled: Boolean) {
        _fields[id]?.let { _fields[id] = it.copy(enabled = enabled) }
    }
    fun setRecordEnabled(id: String, recordEnabled: Boolean) {
        _fields[id]?.let { _fields[id] = it.copy(recordEnabled = recordEnabled) }
    }
    fun setThresholds(id: String, warningThreshold: Double?, criticalThreshold: Double?) {
        _fields[id]?.let { _fields[id] = it.copy(warningThreshold = warningThreshold, criticalThreshold = criticalThreshold) }
    }
    fun setGaugeMax(id: String, gaugeMax: Double) {
        _fields[id]?.let { _fields[id] = it.copy(gaugeMax = gaugeMax) }
    }
    fun setGaugeMin(id: String, gaugeMin: Double) {
        _fields[id]?.let { _fields[id] = it.copy(gaugeMin = gaugeMin) }
    }
    fun setVerifiedNoData(id: String, noData: Boolean) {
        _fields[id]?.let { _fields[id] = it.copy(verifiedNoData = noData) }
    }
    fun setLearnedRange(id: String, min: Double?, max: Double?) {
        _fields[id]?.let { _fields[id] = it.copy(learnedMin = min, learnedMax = max) }
    }
    fun get(id: String): FieldDefinition? = _fields[id]

    fun setAutoPlacement(id: String, autoRow: Int?, autoSlot: Int?) {
        _fields[id]?.let { _fields[id] = it.copy(autoRow = autoRow, autoSlot = autoSlot) }
    }

    /** Puts [ids] first, in that order; every other field keeps its relative order after them. */
    fun reorder(ids: List<String>) {
        val reordered = LinkedHashMap<String, FieldDefinition>()
        for (id in ids) _fields[id]?.let { reordered[id] = it }
        for ((id, field) in _fields) if (id !in reordered) reordered[id] = field
        _fields.clear()
        _fields.putAll(reordered)
    }

    /** Adds catalog entries not already present, without disturbing existing ones' enabled state/order;
     * existing ones pick up catalog label changes. */
    fun mergeCatalog(catalog: List<FieldDefinition>) {
        for (field in catalog) {
            val existing = _fields[field.id]
            _fields[field.id] = existing?.copy(label = field.label) ?: field
            if (field.id !in defaultRanges) defaultRanges[field.id] = field.gaugeMin to field.gaugeMax
        }
    }

    /** Moves [id] one place earlier/later in display order (delta = -1 up, +1 down). */
    fun move(id: String, delta: Int) {
        val order = _fields.keys.toMutableList()
        val from = order.indexOf(id)
        if (from < 0) return
        val to = (from + delta).coerceIn(0, order.size - 1)
        if (to == from) return
        order.removeAt(from)
        order.add(to, id)
        val reordered = LinkedHashMap<String, FieldDefinition>()
        for (key in order) reordered[key] = _fields.getValue(key)
        _fields.clear()
        _fields.putAll(reordered)
    }

    fun toJson(): String = json.encodeToString(fields)

    /**
     * Applies only the user-editable properties from the saved JSON (enabled/recordEnabled/
     * gaugeMax/thresholds/order) on top of the code-defined field, rather than replacing it
     * wholesale — otherwise a stale save from before a code change (e.g. a new [FieldDefinition
     * .decimals] value) would silently override it forever.
     */
    fun loadFromJson(text: String) {
        val loaded: List<FieldDefinition> = json.decodeFromString(text)
        val merged = LinkedHashMap<String, FieldDefinition>()
        for (persisted in loaded) {
            val existing = _fields[persisted.id]
            merged[persisted.id] = existing?.copy(
                enabled = persisted.enabled,
                recordEnabled = persisted.recordEnabled,
                gaugeMax = persisted.gaugeMax,
                gaugeMin = persisted.gaugeMin,
                warningThreshold = persisted.warningThreshold,
                criticalThreshold = persisted.criticalThreshold,
                verifiedNoData = persisted.verifiedNoData,
                learnedMin = persisted.learnedMin,
                learnedMax = persisted.learnedMax,
                autoRow = persisted.autoRow,
                autoSlot = persisted.autoSlot,
            ) ?: persisted
        }
        for ((id, field) in _fields) {
            if (!merged.containsKey(id)) merged[id] = field
        }
        _fields.clear()
        _fields.putAll(merged)
    }

    companion object {
        // Suzuki engine block (mode 0x21, local id 0x00): 210 bytes in 31 CAN frames, about 200 ms per read.
        private val SUZUKI_ENGINE_00 = RequestSpec(targetAddress = 2016, mode = 0x21, params = listOf(0x00), responsePrefixBytes = 0, responseSuffixBytes = 0)

        // Standard OBD-II PIDs: a few bytes each, batched into one request per cycle for the fast gauges.
        private fun obd(vararg pids: Int) = RequestSpec(
            targetAddress = 2016,
            mode = OBD_MODE_CURRENT_DATA,
            params = pids.toList(),
            responsePrefixBytes = 0,
            responseSuffixBytes = 0,
        )
        /**
         * Default gauges: Oil Temperature, Water Temperature, Boost Pressure, RPM, Throttle
         * Position, Speed — all read from a single verified request (mode 0x21,
         * CAN target 0x7E0/2016, local id 0x00) traced to com.malykh.szviewer.common.sdlmod.data
         * .local.engine.Engine_CAN_00_Local$ (its `oilTemp`/`engineTemp`/`boostPressure`/
         * `engineRpm`/`throttle`/`speed`/`voltage` getters), resolved via the
         * SuzukiLocal(Address, part#, part#, localId) constructor pattern in
         * reference/extract_fields.py — see core/src/jvmMain/resources
         * /suzuki-fields-catalog.json entries under "engine.can.00.*" for the same data.
         * Oil/RPM thresholds are the user's specified track-day limits; others are left unset
         * (configurable per-field via ConfigScreen once real limits are known for the vehicle).
         */
        fun withExampleDefaults(): FieldRegistry = FieldRegistry(
            listOf(
                FieldDefinition(
                    id = "engine.oil_temp",
                    label = "Oil Temperature",
                    unit = "\u00B0C",
                    request = RequestSpec(targetAddress = 2016, mode = 0x21, params = listOf(0x00), responsePrefixBytes = 0, responseSuffixBytes = 0),
                    decode = DecodeSpec(skipBytes = 10, byteLength = 1, offset = -40.0),
                    gaugeMin = 0.0,
                    gaugeMax = 120.0,
                    warningThreshold = 110.0,
                    criticalThreshold = 120.0,
                ),
                FieldDefinition(
                    id = "engine.water_temp",
                    label = "Water Temperature",
                    unit = "\u00B0C",
                    request = RequestSpec(targetAddress = 2016, mode = 0x21, params = listOf(0x00), responsePrefixBytes = 0, responseSuffixBytes = 0),
                    decode = DecodeSpec(skipBytes = 44, byteLength = 1, offset = -40.0),
                    gaugeMin = 0.0,
                    gaugeMax = 120.0,
                    warningThreshold = 110.0,
                    criticalThreshold = 120.0,
                ),
                FieldDefinition(
                    id = "engine.boost_pressure",
                    label = "Boost (absolute)",
                    unit = "bar",
                    // OBD PID 0B manifold absolute pressure (kPa); 0.01 converts to bar.
                    request = obd(0x0B),
                    decode = DecodeSpec(skipBytes = 2, byteLength = 1, scale = 0.01),
                    fallback = FieldFallback(SUZUKI_ENGINE_00, DecodeSpec(skipBytes = 149, byteLength = 1, scale = 0.01)),
                    gaugeMin = 0.0,
                    gaugeMax = 2.7,
                    decimals = 2,
                ),
                FieldDefinition(
                    id = "engine.boost_relative",
                    label = "Boost (relative)",
                    unit = "bar",
                    // OBD PID 0B manifold pressure minus PID 33 barometric pressure (kPa), in bar.
                    // Payload is 41 0B <map> 33 <baro>, so baro sits at offset 4.
                    request = obd(0x0B, 0x33),
                    decode = DecodeSpec(skipBytes = 2, byteLength = 1, scale = 0.01, subtractSkipBytes = 4),
                    fallback = FieldFallback(SUZUKI_ENGINE_00, DecodeSpec(skipBytes = 149, byteLength = 1, scale = 0.01, subtractSkipBytes = 46)),
                    gaugeMin = -0.1,
                    gaugeMax = 1.65,
                    warningThreshold = 1.45,
                    criticalThreshold = 1.5,
                    decimals = 2,
                    enabled = false,
                ),
                FieldDefinition(
                    id = "engine.rpm",
                    label = "Engine RPM",
                    unit = "rpm",
                    request = obd(0x0C),
                    decode = DecodeSpec(skipBytes = 2, byteLength = 2, scale = 0.25),
                    fallback = FieldFallback(SUZUKI_ENGINE_00, DecodeSpec(skipBytes = 37, byteLength = 2, scale = 0.25)),
                    gaugeMin = 0.0,
                    gaugeMax = 7000.0,
                    warningThreshold = 6000.0,
                    criticalThreshold = 6500.0,
                    decimals = 0,
                ),
                FieldDefinition(
                    id = "engine.throttle",
                    label = "Throttle Position",
                    unit = "%",
                    request = obd(0x11),
                    decode = DecodeSpec(skipBytes = 2, byteLength = 1, scale = 0.39215686274509803),
                    fallback = FieldFallback(SUZUKI_ENGINE_00, DecodeSpec(skipBytes = 66, byteLength = 1, scale = 0.39215686274509803)),
                    gaugeMin = 0.0,
                    gaugeMax = 100.0,
                ),
                FieldDefinition(
                    id = "engine.speed",
                    label = "Speed",
                    unit = "km/h",
                    request = obd(0x0D),
                    decode = DecodeSpec(skipBytes = 2, byteLength = 1),
                    fallback = FieldFallback(SUZUKI_ENGINE_00, DecodeSpec(skipBytes = 42, byteLength = 1)),
                    gaugeMin = 100.0,
                    gaugeMax = 160.0,
                    warningThreshold = 140.0,
                    criticalThreshold = 150.0,
                    decimals = 0,
                ),
                FieldDefinition(
                    id = "engine.battery_voltage",
                    label = "Battery Voltage",
                    unit = "V",
                    request = RequestSpec(targetAddress = 2016, mode = 0x21, params = listOf(0x00), responsePrefixBytes = 0, responseSuffixBytes = 0),
                    decode = DecodeSpec(skipBytes = 99, byteLength = 2, scale = 0.001),
                    gaugeMin = 9.0,
                    gaugeMax = 16.0,
                    enabled = false,
                ),
            ),
        )
    }
}
