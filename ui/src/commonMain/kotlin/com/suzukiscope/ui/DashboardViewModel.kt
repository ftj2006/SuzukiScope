package com.suzukiscope.ui

import com.suzukiscope.core.field.FieldDefinition
import com.suzukiscope.core.field.FieldRegistry
import com.suzukiscope.core.field.learnedGaugeRange
import com.suzukiscope.core.field.niceScaleMax
import com.suzukiscope.core.field.niceScaleMin
import com.suzukiscope.core.log.LiveDataRecorder
import com.suzukiscope.core.log.Reading
import com.suzukiscope.core.session.Elm327LiveDataSource
import com.suzukiscope.core.elm327.Elm327Protocol
import com.suzukiscope.core.elm327.busProtocol
import com.suzukiscope.core.session.LiveDataSession
import com.suzukiscope.core.session.LiveDataSource
import com.suzukiscope.core.session.SimulatedLiveDataSource
import com.suzukiscope.core.session.currentTimeMillis
import com.suzukiscope.core.session.newSessionId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the dashboard: owns the field registry (configurable, not hardcoded),
 * the live values, and the recorder (peaks + CSV log). Platform-agnostic —
 * androidApp just supplies a [LiveDataSource] and a [CoroutineScope].
 */
class DashboardViewModel(
    private val scope: CoroutineScope,
    private var source: LiveDataSource,
    val registry: FieldRegistry = FieldRegistry.withExampleDefaults(),
    val recorder: LiveDataRecorder = LiveDataRecorder(),
    /** Called after any field config change (enabled/order/thresholds/max) so callers can persist it. */
    private val persist: () -> Unit = {},
    private var exportSink: (List<ExportFile>) -> Unit = {},
    /** Fires once per threshold crossing (e.g. vibration/tone); null = silent (default, desktop). */
    var alertSink: CriticalAlertSink? = null,
) {
    data class LoggingRun(val id: String, val readings: List<Reading>)
    data class ExportFile(val name: String, val contents: String)
    data class ConnectionLog(val id: String, val contents: String)

    private val busFields = MutableStateFlow(registry.fields.filter { it.busProtocol == Elm327Protocol.CAN_11BIT_500K })
    /** Fields of the current vehicle bus only; the other bus's fields keep their own selection for switching back. */
    val fields: StateFlow<List<FieldDefinition>> = busFields.asStateFlow()

    // Every field, on both buses; setting it republishes the current bus's share as [fields].
    private val _fields = object {
        var value: List<FieldDefinition> = registry.fields
            set(all) {
                field = all
                busFields.value = all.filter { it.busProtocol == _vehicleProtocol.value }
            }
    }

    private val _values = MutableStateFlow<Map<String, Double>>(emptyMap())
    val values: StateFlow<Map<String, Double>> = _values.asStateFlow()

    private val _peaks = MutableStateFlow<Map<String, Double>>(emptyMap())
    val peaks: StateFlow<Map<String, Double>> = _peaks.asStateFlow()

    /** Rolling recent-value buffer per field (regardless of logging state) for the History tab. */
    private val _history = MutableStateFlow<Map<String, List<Double>>>(emptyMap())
    val history: StateFlow<Map<String, List<Double>>> = _history.asStateFlow()
    private val _timestampedHistory = MutableStateFlow<Map<String, List<Reading>>>(emptyMap())
    val timestampedHistory: StateFlow<Map<String, List<Reading>>> = _timestampedHistory.asStateFlow()
    private val historySize = 120

    private val _isLogging = MutableStateFlow(false)
    val isLogging: StateFlow<Boolean> = _isLogging.asStateFlow()

    /** Identifies the current/last logging session (e.g. "20260825-101532"), used to name export files. */
    private val _sessionId = MutableStateFlow<String?>(null)
    val sessionId: StateFlow<String?> = _sessionId.asStateFlow()
    private val _completedRuns = MutableStateFlow<List<LoggingRun>>(emptyList())
    val completedRuns: StateFlow<List<LoggingRun>> = _completedRuns.asStateFlow()
    private val _debugLogsEnabled = MutableStateFlow(false)
    val debugLogsEnabled: StateFlow<Boolean> = _debugLogsEnabled.asStateFlow()
    var debugLogsSettingSink: ((Boolean) -> Unit)? = null
    private val _testDataEnabled = MutableStateFlow(false)
    val testDataEnabled: StateFlow<Boolean> = _testDataEnabled.asStateFlow()
    var testDataSettingSink: ((Boolean) -> Unit)? = null
    private val _rawRepliesEnabled = MutableStateFlow(false)
    val rawRepliesEnabled: StateFlow<Boolean> = _rawRepliesEnabled.asStateFlow()
    var rawRepliesSettingSink: ((Boolean) -> Unit)? = null
    private val rawReplies = ArrayList<String>()

    /** The car's diagnostic bus; only fields for this bus can be enabled, and connecting uses it. */
    private val _vehicleProtocol = MutableStateFlow(Elm327Protocol.CAN_11BIT_500K)
    val vehicleProtocol: StateFlow<Elm327Protocol> = _vehicleProtocol.asStateFlow()

    private val _autoLayout = MutableStateFlow(com.suzukiscope.core.layout.AutoLayoutConfig())
    val autoLayout: StateFlow<com.suzukiscope.core.layout.AutoLayoutConfig> = _autoLayout.asStateFlow()
    var autoLayoutSink: ((com.suzukiscope.core.layout.AutoLayoutConfig) -> Unit)? = null
    private val connectionLogs = ArrayList<ConnectionLog>()

    /** Progress/result text for [probeAllFieldsForData]; null when no pass is running/just finished. */
    private val _fieldProbeStatus = MutableStateFlow<String?>(null)
    val fieldProbeStatus: StateFlow<String?> = _fieldProbeStatus.asStateFlow()

    private var pollJob: Job? = null
    private var session = newSession()

    // Back-to-back requests with no gap overwhelmed the adapter's Wi-Fi until it dropped the link;
    // 20 ms (was 50) is under trial for lower latency.
    private fun newSession() = LiveDataSession(
        source,
        fieldsProvider = { fields.value.filter { it.enabled || (_isLogging.value && it.recordEnabled) } },
        intervalMs = if (source is Elm327LiveDataSource) 20 else 40,
    )
    private var currentIoLog = (source as? Elm327LiveDataSource)?.ioLog
    private var currentIoLogId: String? = null
    private var currentConnectionEstablished = false
    private val failedConnectionLogs = mutableListOf<ConnectionLog>()

    // Warning-alert edge detection (fire once on entering warning state, not every poll) and a
    // simple "is the hardware connection still alive" watchdog used by AppState's auto-reconnect.
    private val fieldsInCritical = HashSet<String>()
    private var lastReadingAtMs = currentTimeMillis()
    @Volatile private var lastHardwareReadingAtMs = 0L
    @Volatile private var diagnosticsInProgress = false

    /** Short-term peaks and recent limit hits, drawn the same on the phone and in Android Auto. */
    val markers = GaugeMarkers()

    // Emits once per field newly crossing into critical, for the UI to flash the screen on.
    private val _criticalEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    val criticalEvents: SharedFlow<Unit> = _criticalEvents.asSharedFlow()

    /** Enabled fields whose value hasn't updated recently, so a frozen feed isn't mistaken for a steady value. */
    private val _staleFields = MutableStateFlow<Set<String>>(emptySet())
    val staleFields: StateFlow<Set<String>> = _staleFields.asStateFlow()
    private val fieldReadingAtMs = HashMap<String, Long>()
    private var pollStartedAtMs = 0L

    private suspend fun trackStaleFields() {
        while (true) {
            val now = currentTimeMillis()
            val stale = fields.value.filter { it.enabled }.filter { field ->
                now - (fieldReadingAtMs[field.id] ?: pollStartedAtMs) > STALE_MS
            }.map { it.id }.toSet()
            if (stale != _staleFields.value) _staleFields.value = stale
            kotlinx.coroutines.delay(250)
        }
    }

    fun start() {
        if (pollJob != null) return
        pollStartedAtMs = currentTimeMillis()
        pollJob = scope.launch {
            launch { trackStaleFields() }
            session.readings().collect { reading ->
                // Derived fields only share the engine request; their values come from processEngineReply.
                if (registry.get(reading.fieldId)?.derivedMetric == null) handleReading(reading)
            }
        }
    }

    private fun handleReading(reading: Reading) {
        lastReadingAtMs = currentTimeMillis()
        fieldReadingAtMs[reading.fieldId] = lastReadingAtMs
        _values.value = _values.value + (reading.fieldId to reading.value)
        _history.value = _history.value + (reading.fieldId to ((_history.value[reading.fieldId].orEmpty() + reading.value).takeLast(historySize)))
        _timestampedHistory.value = _timestampedHistory.value + (reading.fieldId to
            ((_timestampedHistory.value[reading.fieldId].orEmpty() + reading).takeLast(historySize)))
        // Only real vehicle data teaches the scale; test data would teach a fake range.
        if (isUsingHardwareSource()) {
            lastHardwareReadingAtMs = lastReadingAtMs
            learnRange(reading.fieldId, reading.value)
            clearNoDataMark(reading.fieldId)
        }
        checkCritical(reading.fieldId, reading.value)
        registry.get(reading.fieldId)?.let { field ->
            markers.record(field.id, reading.value, field.gaugeMax - field.gaugeMin, field.warningThreshold, field.criticalThreshold)
        }
        // Peak = highest value since connecting (or since recording started), shown on every gauge.
        val peak = _peaks.value[reading.fieldId]
        if (peak == null || reading.value > peak) {
            _peaks.value = _peaks.value + (reading.fieldId to reading.value)
        }
        if (_isLogging.value && registry.get(reading.fieldId)?.recordEnabled == true) {
            recorder.record(reading)
        }
    }

    /** Each vehicle's learned power reference, kept on this phone and keyed by VIN. */
    val powerReferences = com.suzukiscope.core.session.PowerReferenceStore()
    var powerReferenceSink: ((String) -> Unit)? = null
    private val _powerReferenceStatus = MutableStateFlow("Power reference: not connected")
    val powerReferenceStatus: StateFlow<String> = _powerReferenceStatus.asStateFlow()
    @Volatile private var currentVin: String? = null
    // Without a VIN nothing is learned or compared, so another car's reference is never applied.
    private val unidentifiedDetector = com.suzukiscope.core.session.PowerLimitDetector()
    private var powerPersistJob: Job? = null

    fun loadPowerReferences(text: String) {
        runCatching { powerReferences.loadJson(text) }
        updatePowerReferenceStatus()
    }

    /** Clears the connected vehicle's reference, e.g. after a tune or hardware change. */
    fun resetPowerReference() {
        val vin = currentVin ?: run { _fieldProbeStatus.value = "Connect to the car first: its VIN selects the reference"; return }
        powerReferences.reset(vin)
        powerReferenceSink?.invoke(powerReferences.toJson())
        updatePowerReferenceStatus()
        _fieldProbeStatus.value = "Power reference cleared for ${vin.take(11)}…; relearning from the next full-pedal pulls"
    }

    fun exportPowerReferences() {
        if (powerReferences.vehicleCount() == 0) {
            _fieldProbeStatus.value = "No power reference learned yet"
            return
        }
        val name = "pj-suzukiscope-power-reference-${newSessionId()}.json"
        exportSink(listOf(ExportFile(name, powerReferences.exportJson(currentTimeMillis()))))
        _fieldProbeStatus.value = "Saved $name in Documents/SuzukiScope (VIN serials left out)"
    }

    private fun updatePowerReferenceStatus() {
        val vin = currentVin
        _powerReferenceStatus.value = when {
            !isUsingHardwareSource() -> "Power reference: not connected (${powerReferences.vehicleCount()} vehicle(s) stored)"
            vin == null -> "Power reference: VIN not read, so nothing is learned or compared"
            else -> powerReferences.detectorFor(vin).coverage().let {
                "Power reference ${vin.take(11)}…: ${it.airflowCells} airflow and ${it.timingCells} timing cells ready; learning"
            }
        }
    }

    private fun processEngineReply(timeMs: Long, reply: ByteArray) {
        if (reply.size < 152 || reply[0] != 0x61.toByte() || reply[1] != 0.toByte()) return
        val derived = fields.value.filter { it.derivedMetric != null && (it.enabled || (_isLogging.value && it.recordEnabled)) }
        if (derived.isEmpty()) return
        fun u(at: Int) = reply[at].toInt() and 0xFF
        val rpm = ((u(37) shl 8) or u(38)) * 0.25
        val kmh = u(42).toDouble()
        val ratios = registry.get("engine.gear")?.decode?.gearRatios.orEmpty()
        val sample = com.suzukiscope.core.session.PowerLimitSample(
            timestampMs = timeMs,
            rpm = rpm,
            speedKmh = kmh,
            gear = com.suzukiscope.core.field.FieldCodec.gear(rpm, kmh, ratios),
            pedalPercent = u(65) / 2.55,
            targetThrottlePercent = u(67) / 2.55,
            manifoldKpa = u(43).toDouble(),
            airflowGps = ((u(40) shl 8) or u(41)) * 0.01,
            advanceDegrees = u(29) * 0.5 - 64,
            fuelCut = u(138) and 1 == 1,
            chargeAirTempC = u(147) - 40.0,
            baroKpa = u(46).toDouble(),
        )
        val vin = currentVin
        val detector = vin?.let { powerReferences.detectorFor(it) } ?: unidentifiedDetector
        val result = detector.observe(sample, learn = vin != null)
        if (result?.referenceChanged == true) schedulePowerPersist()
        for (field in derived) {
            val value = when (field.derivedMetric) {
                com.suzukiscope.core.field.DerivedMetric.POWER_REDUCTION -> result?.reductionPercent
                com.suzukiscope.core.field.DerivedMetric.POWER_REDUCTION_LONG -> result?.longTermReductionPercent
                com.suzukiscope.core.field.DerivedMetric.AIRFLOW_AVAILABLE -> result?.airflowAvailablePercent
                com.suzukiscope.core.field.DerivedMetric.THROTTLE_REDUCTION -> result?.throttleReductionPercent
                com.suzukiscope.core.field.DerivedMetric.TIMING_DEFICIT_SHORT -> result?.timingDeficitDegrees
                com.suzukiscope.core.field.DerivedMetric.TIMING_DEFICIT_LONG -> result?.longTermTimingDeficitDegrees
                null -> null
            } ?: continue
            handleReading(Reading(field.id, timeMs, value))
        }
    }

    private fun schedulePowerPersist() {
        if (powerPersistJob?.isActive == true) return
        powerPersistJob = scope.launch {
            kotlinx.coroutines.delay(10_000)
            powerReferenceSink?.invoke(powerReferences.toJson())
            updatePowerReferenceStatus()
        }
    }

    private fun checkCritical(id: String, value: Double) {
        val threshold = registry.get(id)?.criticalThreshold ?: return
        val inCritical = value >= threshold
        if (inCritical) {
            if (fieldsInCritical.add(id)) {
                // Test data still flashes the screen but stays silent.
                if (isUsingHardwareSource()) alertSink?.onCritical(registry.get(id)!!, value)
                _criticalEvents.tryEmit(Unit)
            }
        } else {
            fieldsInCritical.remove(id)
        }
    }

    /** True while backed by real hardware (vs. simulated) — used by the auto-reconnect watchdog. */
    fun isUsingHardwareSource(): Boolean = source is Elm327LiveDataSource
    fun currentTransportName(): String = source.let { (it as? Elm327LiveDataSource)?.transportName.orEmpty() }

    /** Milliseconds since the last successful reading — a long gap while on hardware means the
     * connection has likely dropped even though no exception ever reached this layer. */
    fun millisSinceLastReading(): Long = currentTimeMillis() - lastReadingAtMs

    fun lastHardwareReadingTimeMs(): Long = lastHardwareReadingAtMs
    fun isDiagnosticsInProgress(): Boolean = diagnosticsInProgress
    fun hasPollableFields(): Boolean = fields.value.any {
        (it.enabled || (_isLogging.value && it.recordEnabled)) && source.canPoll(it)
    }

    /** True once the adapter link itself has failed, so reconnecting needn't wait for the stall timeout. */
    fun isTransportBroken(): Boolean = (source as? Elm327LiveDataSource)?.transportBroken == true

    /** Notes a phone-side event (e.g. Wi-Fi change) in the adapter log so it lines up with the traffic. */
    fun logConnectionEvent(message: String) {
        (source as? Elm327LiveDataSource)?.ioLog?.append(com.suzukiscope.core.log.IoDirection.RECEIVED, message)
    }

    /**
     * Learns each field's real operating range from the vehicle across drives (persisted), and
     * scales its gauge to that range. The first drives are jumpy; it settles as extremes are seen.
     */
    private fun learnRange(id: String, value: Double) {
        val field = registry.get(id) ?: return
        val lo = field.learnedMin
        val hi = field.learnedMax
        val newLow = sustainedOutOfRange("$id:min", lo == null || value < lo)
        val newHigh = sustainedOutOfRange("$id:max", hi == null || value > hi)
        if (!newLow && !newHigh) return
        val learnedMin = if (newLow || lo == null) value else lo
        val learnedMax = if (newHigh || hi == null) value else hi
        registry.setLearnedRange(id, learnedMin, learnedMax)
        val (gaugeMin, gaugeMax) = learnedScale(field, learnedMin, learnedMax)
        // Only republish fields when the rounded scale actually changes; this runs inside the polling loop.
        if (gaugeMin != field.gaugeMin || gaugeMax != field.gaugeMax) {
            registry.setGaugeMin(id, gaugeMin)
            registry.setGaugeMax(id, gaugeMax)
            _fields.value = registry.fields
        }
        schedulePersist()
    }

    private var persistJob: Job? = null

    // Saving the whole field config is slow; batch learned-range saves instead of writing per reading.
    private fun schedulePersist() {
        if (persistJob?.isActive == true) return
        persistJob = scope.launch {
            kotlinx.coroutines.delay(10_000)
            persist()
        }
    }

    // A field that was marked "no data" by an earlier test (e.g. one run on the wrong protocol)
    // but now returns real readings is clearly fine; unmark it so it isn't hidden in Config.
    private fun clearNoDataMark(id: String) {
        if (registry.get(id)?.verifiedNoData != true) return
        registry.setVerifiedNoData(id, false)
        _fields.value = registry.fields
        schedulePersist()
    }

    /**
     * Scale for a learned range: it only ever widens the field's built-in scale, and spans at least
     * [MIN_SPAN_FRACTION] of its magnitude, so readings taken while parked can't shrink it to a sliver.
     */
    private fun learnedScale(field: FieldDefinition, learnedMin: Double, learnedMax: Double): Pair<Double, Double> {
        var lo = learnedMin
        var hi = learnedMax
        registry.defaultRange(field.id)?.let { (defaultMin, defaultMax) ->
            lo = minOf(lo, defaultMin)
            hi = maxOf(hi, defaultMax)
        }
        val minSpan = maxOf(kotlin.math.abs(lo), kotlin.math.abs(hi)) * MIN_SPAN_FRACTION
        if (hi - lo < minSpan) {
            val mid = (lo + hi) / 2
            lo = mid - minSpan / 2
            hi = mid + minSpan / 2
        }
        return learnedGaugeRange(
            lo,
            hi,
            listOfNotNull(field.warningThreshold, field.criticalThreshold),
            stepSize = if (field.id == "engine.boost_relative") 0.1 else null,
        )
    }

    /** Widens any gauge whose saved scale is narrower than [learnedScale] allows (fixes scales learned before that rule). */
    fun widenLearnedScales() {
        var changed = false
        for (field in registry.fields) {
            val lo = field.learnedMin ?: continue
            val hi = field.learnedMax ?: continue
            val (scaleMin, scaleMax) = learnedScale(field, lo, hi)
            val newMin = minOf(field.gaugeMin, scaleMin)
            val newMax = maxOf(field.gaugeMax, scaleMax)
            if (newMin != field.gaugeMin || newMax != field.gaugeMax) {
                registry.setGaugeMin(field.id, newMin)
                registry.setGaugeMax(field.id, newMax)
                changed = true
            }
        }
        if (changed) {
            _fields.value = registry.fields
            persist()
        }
    }

    /** Forgets a field's learned range so it is re-learned from the next drive. */
    fun resetLearnedRange(id: String) {
        registry.setLearnedRange(id, null, null)
        outOfRangeStreak.remove("$id:min")
        outOfRangeStreak.remove("$id:max")
        _fields.value = registry.fields
        persist()
    }

    // A single garbled frame (e.g. 0xFF decoding to 215 °C) must not become a learned extreme,
    // so a new min/max only counts once several readings in a row agree.
    private val outOfRangeStreak = HashMap<String, Int>()
    private fun sustainedOutOfRange(key: String, outOfRange: Boolean): Boolean {
        if (!outOfRange) {
            outOfRangeStreak.remove(key)
            return false
        }
        val streak = (outOfRangeStreak[key] ?: 0) + 1
        outOfRangeStreak[key] = streak
        return streak >= WIDEN_AFTER_READINGS
    }

    private companion object {
        const val WIDEN_AFTER_READINGS = 3
        const val MIN_SPAN_FRACTION = 0.2
        const val STALE_MS = 2000L
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        _staleFields.value = emptySet()
    }

    suspend fun disconnectHardware() {
        if (_isLogging.value) stopLogging()
        persistCurrentConnectionLog()
        powerReferenceSink?.invoke(powerReferences.toJson())
        (source as? Elm327LiveDataSource)?.disconnect()
        // Idle until the user connects again or picks Test Data; test data never starts by itself.
        useSource(SimulatedLiveDataSource(), startPolling = false)
    }

    /** Closes the current adapter socket without switching to simulated data (used before reconnecting). */
    suspend fun closeHardwareTransport() {
        (source as? Elm327LiveDataSource)?.let { runCatching { it.disconnect() } }
    }

    fun stopTestData() {
        if (!isUsingHardwareSource()) {
            finishCurrentRun()
            stop()
            recorder.clear()
            _completedRuns.value = emptyList()
            _sessionId.value = null
            _values.value = emptyMap()
            _history.value = emptyMap()
            _peaks.value = emptyMap()
            markers.clear()
            _isLogging.value = false
        }
    }

    /** Swaps the live-data backend (e.g. simulated -> real ELM327 hardware); [startPolling] null = keep polling if it was. */
    fun useSource(newSource: LiveDataSource, startPolling: Boolean? = null) {
        val wasRunning = pollJob != null
        stop()
        (source as? Elm327LiveDataSource)?.stopKeepAlive()
        (source as? Elm327LiveDataSource)?.onRawReply = null
        source = newSource
        (newSource as? Elm327LiveDataSource)?.startKeepAlive(scope)
        (newSource as? Elm327LiveDataSource)?.onRawReply = ::recordRawReply
        session = newSession()
        (newSource as? Elm327LiveDataSource)?.ioLog?.let { currentIoLog = it }
        currentVin = (newSource as? Elm327LiveDataSource)?.vin
        updatePowerReferenceStatus()
        _values.value = emptyMap()
        _peaks.value = emptyMap()
        _timestampedHistory.value = emptyMap()
        markers.clear()
        fieldsInCritical.clear()
        fieldReadingAtMs.clear()
        lastReadingAtMs = currentTimeMillis()
        if (startPolling ?: (wasRunning || newSource is Elm327LiveDataSource)) start()
    }

    /** Buffers failed attempts for the next combined connection-log export. */
    fun attachIoLog(ioLog: com.suzukiscope.core.log.Elm327IoLog?) {
        currentIoLog?.let { log ->
            val contents = log.toText()
            if (contents != "No adapter traffic logged yet.") {
                if (currentConnectionEstablished) {
                    persistCurrentConnectionLog()
                } else {
                    failedConnectionLogs += ConnectionLog(currentIoLogId ?: newSessionId(), contents)
                    currentIoLog = null
                    currentIoLogId = null
                }
            }
        }
        currentIoLog = ioLog
        currentIoLogId = if (ioLog != null) newSessionId() else null
        currentConnectionEstablished = false
    }

    fun markConnectionEstablished() { currentConnectionEstablished = true }

    fun setExportSink(sink: (List<ExportFile>) -> Unit) { exportSink = sink }

    fun persistCurrentConnectionLog() {
        if (!_debugLogsEnabled.value) return
        val combined = combinedCurrentConnectionLog() ?: return
        connectionLogs += combined
        exportSink(listOf(ExportFile("pj-suzukiscope-connection-log-${combined.id}.txt", combined.contents)))
        failedConnectionLogs.clear()
        currentIoLog = null
        currentIoLogId = null
        currentConnectionEstablished = false
    }

    private fun combinedCurrentConnectionLog(): ConnectionLog? {
        val current = currentIoLog?.toText()?.takeUnless { it == "No adapter traffic logged yet." }
        if (failedConnectionLogs.isEmpty()) return current?.let { ConnectionLog(currentIoLogId ?: newSessionId(), it) }
        val output = StringBuilder()
        val repeatedErrors = LinkedHashMap<String, Pair<String, Int>>()
        failedConnectionLogs.forEachIndexed { index, failed ->
            output.append("--- failed connection attempt ${index + 1} ---\n")
            failed.contents.lineSequence().forEach { line ->
                if ("[ERR]" in line) {
                    val key = line.substringAfter(' ').trim()
                    val prior = repeatedErrors[key]
                    repeatedErrors[key] = (prior?.first ?: line) to ((prior?.second ?: 0) + 1)
                    if (prior == null) output.appendLine(line)
                } else if (line.isNotEmpty()) {
                    output.appendLine(line)
                }
            }
        }
        if (current != null) output.append("--- active connection ---\n").appendLine(current)
        val repeats = repeatedErrors.values.filter { it.second > 1 }
        if (repeats.isNotEmpty()) {
            output.appendLine("--- repeated errors across failed attempts ---")
            repeats.forEach { (line, count) -> output.appendLine("${count}x ${line.substringAfter(' ').trim()}") }
        }
        val id = failedConnectionLogs.firstOrNull()?.id ?: currentIoLogId ?: newSessionId()
        return ConnectionLog(id, output.toString())
    }

    fun setFieldEnabled(id: String, enabled: Boolean) {
        registry.setEnabled(id, enabled)
        // Newly shown and hidden fields both belong to the remaining rows.
        registry.setAutoPlacement(id, null, null)
        _fields.value = registry.fields
        persist()
    }

    fun setAutoLayout(config: com.suzukiscope.core.layout.AutoLayoutConfig) {
        _autoLayout.value = config
        autoLayoutSink?.invoke(config)
    }

    /**
     * Applies a drag in the dashboard field list: [order] is the enabled fields in their new
     * order, [placements] each one's Android Auto row and slot (null row = remaining rows).
     */
    fun arrangeDashboard(order: List<String>, placements: Map<String, com.suzukiscope.core.layout.Placement>) {
        for (id in order) placements[id]?.let { registry.setAutoPlacement(id, it.row, it.slot) }
        registry.reorder(order)
        _fields.value = registry.fields
        persist()
    }

    fun setDebugLogsEnabled(enabled: Boolean) {
        _debugLogsEnabled.value = enabled
        debugLogsSettingSink?.invoke(enabled)
        if (!enabled) {
            persistCurrentConnectionLog()
            currentIoLog = null
            currentIoLogId = null
            currentConnectionEstablished = false
            failedConnectionLogs.clear()
            connectionLogs.clear()
        }
    }
    fun setTestDataEnabled(enabled: Boolean) {
        _testDataEnabled.value = enabled
        testDataSettingSink?.invoke(enabled)
    }

    fun setRawRepliesEnabled(enabled: Boolean) {
        _rawRepliesEnabled.value = enabled
        rawRepliesSettingSink?.invoke(enabled)
    }

    @kotlin.jvm.Synchronized
    private fun recordRawReply(timeMs: Long, module: String, request: String, reply: ByteArray) {
        if (module == "7E0" && request == "2100") processEngineReply(timeMs, reply)
        if (!_isLogging.value || !_rawRepliesEnabled.value) return
        rawReplies += "$timeMs $module $request " + reply.joinToString("") { "%02X".format(it) }
    }

    @kotlin.jvm.Synchronized
    private fun takeRawReplies(): List<String> = rawReplies.toList().also { rawReplies.clear() }

    /** Switches the vehicle bus; each bus remembers which of its fields were enabled. */
    fun setVehicleProtocol(protocol: Elm327Protocol) {
        _vehicleProtocol.value = protocol
        _fields.value = registry.fields
        persist()
    }

    fun setRecordEnabled(id: String, recordEnabled: Boolean) {
        registry.setRecordEnabled(id, recordEnabled)
        _fields.value = registry.fields
        persist()
    }

    fun setThresholds(id: String, warningThreshold: Double?, criticalThreshold: Double?) {
        registry.setThresholds(id, warningThreshold, criticalThreshold)
        // Setting a warning/critical above the current gauge max would clip the threshold's tick
        // mark off the visible arc, so the max must always cover at least the highest one set.
        listOfNotNull(warningThreshold, criticalThreshold).maxOrNull()?.let { highest ->
            val field = registry.get(id)
            val niceMax = niceScaleMax(highest)
            if (field != null && niceMax > field.gaugeMax) registry.setGaugeMax(id, niceMax)
        }
        _fields.value = registry.fields
        persist()
    }

    fun setGaugeMax(id: String, gaugeMax: Double) {
        registry.setGaugeMax(id, gaugeMax)
        _fields.value = registry.fields
        persist()
    }

    fun setGaugeMin(id: String, gaugeMin: Double) {
        registry.setGaugeMin(id, gaugeMin)
        _fields.value = registry.fields
        persist()
    }

    fun addField(field: FieldDefinition) {
        registry.add(field)
        _fields.value = registry.fields
        persist()
    }

    /** Reorders a field earlier/later in the dashboard (delta = -1 up, +1 down). */
    fun moveField(id: String, delta: Int) {
        registry.move(id, delta)
        _fields.value = registry.fields
        persist()
    }

    /** Merges in every field the extraction catalog found (all modules, disabled by default). */
    fun loadFullCatalog(catalog: List<FieldDefinition>) {
        registry.mergeCatalog(catalog)
        _fields.value = registry.fields
        persist()
        widenLearnedScales()
    }

    /**
     * One-off diagnostic pass: polls every field in the registry once (not just enabled ones) on
     * this vehicle over the current hardware connection, and records which ones actually
     * returned data vs failed/timed out — see [FieldDefinition.verifiedNoData]. Fields whose
     * request needs the other protocol (CAN vs KWP) than the one currently connected can't be
     * tested right now, so they're left untouched rather than wrongly marked "no data".
     */
    suspend fun probeAllFieldsForData() = withPollingPaused("Field test", requireCan = false) { liveSource, progress ->
        val candidates = registry.fields.filter { (it.request.responsePrefixBytes == 0) == (liveSource.protocol == Elm327Protocol.CAN_11BIT_500K) }
        liveSource.resetBusState()
        liveSource.beginCycle(emptyList())
        var withData = 0
        var noData = 0
        for ((index, field) in candidates.withIndex()) {
            progress("Testing field ${index + 1}/${candidates.size}: ${field.label}")
            val hasData = try {
                liveSource.poll(field)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            registry.setVerifiedNoData(field.id, !hasData)
            if (hasData) withData++ else noData++
        }
        _fields.value = registry.fields
        persist()
        val skipped = registry.fields.size - candidates.size
        "Done: $withData with data, $noData with none" +
            (if (skipped > 0) " ($skipped skipped \u2014 need the other protocol connected)" else "")
    }

    /** Pauses polling and probes the engine ECU's data tables (see [Elm327LiveDataSource.probeLocalTables]). */
    suspend fun probeEcuTables() = withPollingPaused("Table probe") { liveSource, progress ->
        val summary = liveSource.probeLocalTables { index, total -> progress("Probing ECU table $index/$total") }
        "Table probe done: $summary. Export the connection log to share it."
    }

    /** Pauses polling, reads every known engine table and saves them to a dated file. */
    suspend fun saveEcuSnapshot() = withPollingPaused("ECU snapshot") { liveSource, progress ->
        val snapshot = liveSource.readEcuSnapshot(progress)
        val file = saveSnapshotFile(snapshot)
        rememberSnapshot(snapshot)
        "ECU snapshot saved as $file in Documents/SuzukiScope"
    }

    // Engine ECU tables from the latest snapshot, kept across restarts via [ecuTablesSink].
    private val _ecuTables = MutableStateFlow<Map<Int, ByteArray>>(emptyMap())
    val ecuTables: StateFlow<Map<Int, ByteArray>> = _ecuTables.asStateFlow()
    var ecuTablesSink: ((String) -> Unit)? = null

    fun loadEcuTables(text: String) {
        _ecuTables.value = com.suzukiscope.core.ecu.EcuTables.fromText(text)
    }

    private fun saveSnapshotFile(snapshot: com.suzukiscope.core.session.EcuSnapshot, suffix: String = ""): String {
        val id = newSessionId()
        val name = "pj-suzukiscope-ecu-snapshot-$id$suffix.txt"
        exportSink(listOf(ExportFile(name, "Snapshot $id$suffix\n" + snapshot.text)))
        return name
    }

    /**
     * Reads, saves (with [suffix] in the file name) and remembers an engine snapshot, refreshing the
     * odometer, learned trims and fault history. Call only inside [withBusPaused].
     */
    suspend fun snapshotWhilePaused(suffix: String): String {
        val liveSource = source as? Elm327LiveDataSource ?: error("not connected to the adapter")
        // Another feature may have re-addressed the adapter since polling stopped.
        liveSource.resetBusState()
        val snapshot = liveSource.readEcuSnapshot { lastReadingAtMs = currentTimeMillis() }
        check(snapshot.tables.isNotEmpty()) { "the ECU returned no tables" }
        val file = saveSnapshotFile(snapshot, suffix)
        rememberSnapshot(snapshot)
        logConnectionEvent("ECU snapshot saved as $file")
        return file
    }

    private fun rememberSnapshot(snapshot: com.suzukiscope.core.session.EcuSnapshot) {
        // Keep earlier tables that failed this time, so one timeout doesn't blank a value.
        val merged = _ecuTables.value + snapshot.tables
        _ecuTables.value = merged
        ecuTablesSink?.invoke(com.suzukiscope.core.ecu.EcuTables.toText(merged))
    }

    /**
     * Runs [block] with live polling stopped, then has polling re-address the ECU before resuming.
     * [block] calls its heartbeat after each request so the reconnect watchdog doesn't see a stall.
     */
    suspend fun withBusPaused(block: suspend (heartbeat: () -> Unit) -> Unit) {
        val wasRunning = pollJob != null
        val current = source
        diagnosticsInProgress = true
        stop()
        try {
            block { lastReadingAtMs = currentTimeMillis() }
        } finally {
            diagnosticsInProgress = false
            (current as? Elm327LiveDataSource)?.resetBusState()
            lastReadingAtMs = currentTimeMillis()
            if (wasRunning && source === current) start()
        }
    }

    private suspend fun withPollingPaused(
        task: String,
        requireCan: Boolean = true,
        block: suspend (Elm327LiveDataSource, (String) -> Unit) -> String,
    ) {
        val liveSource = source as? Elm327LiveDataSource
        if (liveSource == null || (requireCan && liveSource.protocol != Elm327Protocol.CAN_11BIT_500K)) {
            _fieldProbeStatus.value = if (requireCan) "Connect to the real adapter on CAN first" else "Connect to the real adapter first"
            return
        }
        val wasRunning = pollJob != null
        diagnosticsInProgress = true
        stop()
        try {
            _fieldProbeStatus.value = block(liveSource) { message ->
                // Keeps the auto-reconnect watchdog from treating the paused polling as a stall.
                lastReadingAtMs = currentTimeMillis()
                _fieldProbeStatus.value = message
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _fieldProbeStatus.value = "$task failed: ${e.message}"
        } finally {
            diagnosticsInProgress = false
            lastReadingAtMs = currentTimeMillis()
            if (wasRunning && source === liveSource) start()
        }
    }

    /** Runs a diagnostic task in the app-wide scope, so leaving the screen doesn't cancel it mid-command. */
    fun launch(block: suspend DashboardViewModel.() -> Unit) {
        scope.launch { block() }
    }

    /** Re-syncs [fields] after external registry changes (e.g. loading persisted config from disk). */
    fun refreshFields() {
        _fields.value = registry.fields
    }

    fun startLogging() {
        finishCurrentRun()
        recorder.clear()
        takeRawReplies()
        _peaks.value = emptyMap()
        _sessionId.value = newSessionId()
        _isLogging.value = true
    }

    fun stopLogging() {
        _isLogging.value = false
        finishCurrentRun()
        recorder.clear()
    }

    private fun finishCurrentRun() {
        val readings = recorder.readings()
        val id = _sessionId.value
        val raw = takeRawReplies()
        if (id != null && raw.isNotEmpty()) {
            exportSink(listOf(ExportFile("pj-suzukiscope-raw-$id.txt", "# time_ms module request reply\n" + raw.joinToString("\n"))))
        }
        if (id != null && readings.isNotEmpty() && _completedRuns.value.none { it.id == id }) {
            _completedRuns.value = _completedRuns.value + LoggingRun(id, readings)
            exportSink(listOf(ExportFile("pj-suzukiscope-run-$id.csv", recorder.toCsv(
                labelFor = { fieldId -> registry.get(fieldId)?.label ?: fieldId },
                unitFor = { fieldId -> registry.get(fieldId)?.unit ?: "" },
                readings = readings,
            ))))
        }
    }

    fun runsForExport(): List<LoggingRun> {
        val current = recorder.readings()
        val id = _sessionId.value
        return if (id != null && current.isNotEmpty() && _completedRuns.value.none { it.id == id }) {
            _completedRuns.value + LoggingRun(id, current)
        } else {
            _completedRuns.value
        }
    }

    fun exportCsv(): String = recorder.toCsv(
        labelFor = { id -> registry.get(id)?.label ?: id },
        unitFor = { id -> registry.get(id)?.unit ?: "" },
    )

    fun exportRunFiles(runIds: Set<String>): List<ExportFile> = runsForExport()
        .filter { it.id in runIds }
        .map { run ->
            ExportFile(
                "pj-suzukiscope-run-${run.id}.csv",
                recorder.toCsv(
                    labelFor = { id -> registry.get(id)?.label ?: id },
                    unitFor = { id -> registry.get(id)?.unit ?: "" },
                    readings = run.readings,
                ),
            )
        }

    fun connectionLogsForExport(): List<ConnectionLog> {
        return connectionLogs + listOfNotNull(combinedCurrentConnectionLog())
    }

    /** Unique per-session file name so exporting multiple sessions doesn't overwrite each other. */
    fun exportCsvFileName(): String = "pj-suzukiscope-log-${_sessionId.value ?: newSessionId()}.csv"
    fun exportLogFileName(): String = "pj-suzukiscope-connection-log-${_sessionId.value ?: newSessionId()}.txt"

    /** Raw ELM327 AT-command/response trace for the active hardware connection (if any) —
     * useful for diagnosing the first few real vehicle connections, safe to ignore otherwise. */
    fun exportIoLog(): String = combinedCurrentConnectionLog()?.contents ?: "No hardware connection active — nothing logged yet."
}
