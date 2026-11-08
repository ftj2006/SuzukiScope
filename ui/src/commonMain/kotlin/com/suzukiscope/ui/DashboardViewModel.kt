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

    private val _fields = MutableStateFlow(registry.fields)
    val fields: StateFlow<List<FieldDefinition>> = _fields.asStateFlow()

    private val _values = MutableStateFlow<Map<String, Double>>(emptyMap())
    val values: StateFlow<Map<String, Double>> = _values.asStateFlow()

    private val _peaks = MutableStateFlow<Map<String, Double>>(emptyMap())
    val peaks: StateFlow<Map<String, Double>> = _peaks.asStateFlow()

    /** Rolling recent-value buffer per field (regardless of logging state) for the History tab. */
    private val _history = MutableStateFlow<Map<String, List<Double>>>(emptyMap())
    val history: StateFlow<Map<String, List<Double>>> = _history.asStateFlow()
    private val historySize = 120

    private val _isLogging = MutableStateFlow(false)
    val isLogging: StateFlow<Boolean> = _isLogging.asStateFlow()

    /** Identifies the current/last logging session (e.g. "20260825-101532"), used to name export files. */
    private val _sessionId = MutableStateFlow<String?>(null)
    val sessionId: StateFlow<String?> = _sessionId.asStateFlow()
    private val _completedRuns = MutableStateFlow<List<LoggingRun>>(emptyList())
    val completedRuns: StateFlow<List<LoggingRun>> = _completedRuns.asStateFlow()
    private val _debugLogsEnabled = MutableStateFlow(true)
    val debugLogsEnabled: StateFlow<Boolean> = _debugLogsEnabled.asStateFlow()
    var debugLogsSettingSink: ((Boolean) -> Unit)? = null
    private val _testDataEnabled = MutableStateFlow(true)
    val testDataEnabled: StateFlow<Boolean> = _testDataEnabled.asStateFlow()

    /** The car's diagnostic bus; only fields for this bus can be enabled, and connecting uses it. */
    private val _vehicleProtocol = MutableStateFlow(Elm327Protocol.CAN_11BIT_500K)
    val vehicleProtocol: StateFlow<Elm327Protocol> = _vehicleProtocol.asStateFlow()
    var vehicleProtocolSink: ((Elm327Protocol) -> Unit)? = null
    private val connectionLogs = ArrayList<ConnectionLog>()

    /** Progress/result text for [probeAllFieldsForData]; null when no pass is running/just finished. */
    private val _fieldProbeStatus = MutableStateFlow<String?>(null)
    val fieldProbeStatus: StateFlow<String?> = _fieldProbeStatus.asStateFlow()

    private var pollJob: Job? = null
    private var session = newSession()

    // Back-to-back requests with no gap overwhelmed the adapter's Wi-Fi until it dropped the link.
    private fun newSession() = LiveDataSession(
        source,
        fieldsProvider = { _fields.value.filter { it.enabled || (_isLogging.value && it.recordEnabled) } },
        intervalMs = if (source is Elm327LiveDataSource) 50 else 40,
    )
    private var currentIoLog = (source as? Elm327LiveDataSource)?.ioLog
    private var currentIoLogId: String? = null

    // Warning-alert edge detection (fire once on entering warning state, not every poll) and a
    // simple "is the hardware connection still alive" watchdog used by AppState's auto-reconnect.
    private val fieldsInCritical = HashSet<String>()
    private var lastReadingAtMs = currentTimeMillis()

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
            val stale = _fields.value.filter { it.enabled }.filter { field ->
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
                lastReadingAtMs = currentTimeMillis()
                fieldReadingAtMs[reading.fieldId] = lastReadingAtMs
                _values.value = _values.value + (reading.fieldId to reading.value)
                _history.value = _history.value + (reading.fieldId to ((_history.value[reading.fieldId].orEmpty() + reading.value).takeLast(historySize)))
                // Only real vehicle data teaches the scale; test data would teach a fake range.
                if (isUsingHardwareSource()) {
                    learnRange(reading.fieldId, reading.value)
                    clearNoDataMark(reading.fieldId)
                }
                checkCritical(reading.fieldId, reading.value)
                // Peak = highest value since connecting (or since recording started), shown on every gauge.
                val peak = _peaks.value[reading.fieldId]
                if (peak == null || reading.value > peak) {
                    _peaks.value = _peaks.value + (reading.fieldId to reading.value)
                }
                if (_isLogging.value && registry.get(reading.fieldId)?.recordEnabled == true) {
                    recorder.record(reading)
                }
            }
        }
    }

    private fun checkCritical(id: String, value: Double) {
        val threshold = registry.get(id)?.criticalThreshold ?: return
        val inCritical = value >= threshold
        if (inCritical) {
            if (fieldsInCritical.add(id)) {
                alertSink?.onCritical(registry.get(id)!!, value)
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

    /** True once the adapter link itself has failed, so reconnecting needn't wait for the stall timeout. */
    fun isTransportBroken(): Boolean = (source as? Elm327LiveDataSource)?.transportBroken == true

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
        val (gaugeMin, gaugeMax) = learnedGaugeRange(learnedMin, learnedMax, listOfNotNull(field.warningThreshold, field.criticalThreshold))
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
        const val STALE_MS = 2000L
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        _staleFields.value = emptySet()
    }

    suspend fun disconnectHardware() {
        persistCurrentConnectionLog()
        (source as? Elm327LiveDataSource)?.disconnect()
        useSource(SimulatedLiveDataSource())
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
            _isLogging.value = false
        }
    }

    /** Swaps the live-data backend (e.g. simulated -> real ELM327 hardware) and restarts polling if running. */
    fun useSource(newSource: LiveDataSource) {
        val wasRunning = pollJob != null
        stop()
        (source as? Elm327LiveDataSource)?.stopKeepAlive()
        source = newSource
        (newSource as? Elm327LiveDataSource)?.startKeepAlive(scope)
        session = newSession()
        (newSource as? Elm327LiveDataSource)?.ioLog?.let { currentIoLog = it }
        _values.value = emptyMap()
        _peaks.value = emptyMap()
        fieldsInCritical.clear()
        fieldReadingAtMs.clear()
        lastReadingAtMs = currentTimeMillis()
        if (wasRunning || newSource is Elm327LiveDataSource) start()
    }

    /** Makes a connection attempt's log exportable even if it never becomes the active source
     * (e.g. it failed to connect) — otherwise there'd be nothing to inspect via "Export log". */
    fun attachIoLog(ioLog: com.suzukiscope.core.log.Elm327IoLog?) {
        persistCurrentConnectionLog()
        currentIoLog?.let { log ->
            val contents = log.toText()
            if (contents != "No adapter traffic logged yet.") {
                connectionLogs += ConnectionLog(newSessionId(), contents)
            }
        }
        currentIoLog = ioLog
        currentIoLogId = if (ioLog != null) newSessionId() else null
    }

    fun setExportSink(sink: (List<ExportFile>) -> Unit) { exportSink = sink }

    fun persistCurrentConnectionLog() {
        if (!_debugLogsEnabled.value) return
        val log = currentIoLog ?: return
        val contents = log.toText()
        if (contents != "No adapter traffic logged yet.") {
            val id = currentIoLogId ?: newSessionId()
            connectionLogs += ConnectionLog(id, contents)
            exportSink(listOf(ExportFile("pj-suzukiscope-connection-log-$id.txt", contents)))
            currentIoLog = null
            currentIoLogId = null
        }
    }

    fun setFieldEnabled(id: String, enabled: Boolean) {
        registry.setEnabled(id, enabled)
        _fields.value = registry.fields
        persist()
    }

    fun setDebugLogsEnabled(enabled: Boolean) {
        _debugLogsEnabled.value = enabled
        debugLogsSettingSink?.invoke(enabled)
        if (!enabled) {
            persistCurrentConnectionLog()
            currentIoLog = null
            connectionLogs.clear()
        }
    }
    fun setTestDataEnabled(enabled: Boolean) { _testDataEnabled.value = enabled }

    /** Switches the vehicle bus and disables any enabled fields that belong to the other one. */
    fun setVehicleProtocol(protocol: Elm327Protocol) {
        _vehicleProtocol.value = protocol
        registry.fields.filter { it.enabled && it.busProtocol != protocol }.forEach { registry.setEnabled(it.id, false) }
        _fields.value = registry.fields
        persist()
        vehicleProtocolSink?.invoke(protocol)
    }

    /** Bus most enabled fields use (CAN when tied or none) — the starting point before the user picks one. */
    fun inferVehicleProtocol(): Elm327Protocol {
        val enabled = registry.fields.filter { it.enabled }
        val kwp = enabled.count { it.busProtocol == Elm327Protocol.KWP_FAST }
        return if (kwp * 2 > enabled.size) Elm327Protocol.KWP_FAST else Elm327Protocol.CAN_11BIT_500K
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
    }

    /**
     * One-off diagnostic pass: polls every field in the registry once (not just enabled ones) on
     * this vehicle over the current hardware connection, and records which ones actually
     * returned data vs failed/timed out — see [FieldDefinition.verifiedNoData]. Fields whose
     * request needs the other protocol (CAN vs KWP) than the one currently connected can't be
     * tested right now, so they're left untouched rather than wrongly marked "no data".
     */
    suspend fun probeAllFieldsForData() {
        val liveSource = source as? Elm327LiveDataSource ?: run {
            _fieldProbeStatus.value = "Connect to the real adapter first"
            return
        }
        val candidates = registry.fields.filter { (it.request.responsePrefixBytes == 0) == (liveSource.protocol == Elm327Protocol.CAN_11BIT_500K) }
        liveSource.beginCycle(emptyList())
        var withData = 0
        var noData = 0
        for ((index, field) in candidates.withIndex()) {
            _fieldProbeStatus.value = "Testing field ${index + 1}/${candidates.size}: ${field.label}"
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
        _fieldProbeStatus.value = "Done: $withData with data, $noData with none" +
            (if (skipped > 0) " ($skipped skipped \u2014 need the other protocol connected)" else "")
    }

    /** Re-syncs [fields] after external registry changes (e.g. loading persisted config from disk). */
    fun refreshFields() {
        _fields.value = registry.fields
    }

    fun startLogging() {
        finishCurrentRun()
        recorder.clear()
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
        val current = currentIoLog
        if (current != null) {
            val contents = current.toText()
            if (contents != "No adapter traffic logged yet.") {
                return connectionLogs + ConnectionLog(_sessionId.value ?: newSessionId(), contents)
            }
        }
        return connectionLogs.toList()
    }

    /** Unique per-session file name so exporting multiple sessions doesn't overwrite each other. */
    fun exportCsvFileName(): String = "pj-suzukiscope-log-${_sessionId.value ?: newSessionId()}.csv"
    fun exportLogFileName(): String = "pj-suzukiscope-connection-log-${_sessionId.value ?: newSessionId()}.txt"

    /** Raw ELM327 AT-command/response trace for the active hardware connection (if any) —
     * useful for diagnosing the first few real vehicle connections, safe to ignore otherwise. */
    fun exportIoLog(): String = currentIoLog?.toText() ?: "No hardware connection active — nothing logged yet."
}
