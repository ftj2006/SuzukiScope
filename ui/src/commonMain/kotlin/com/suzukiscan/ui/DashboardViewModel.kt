package com.suzukiscan.ui

import com.suzukiscan.core.field.FieldDefinition
import com.suzukiscan.core.field.FieldRegistry
import com.suzukiscan.core.field.niceScaleMax
import com.suzukiscan.core.log.LiveDataRecorder
import com.suzukiscan.core.session.Elm327LiveDataSource
import com.suzukiscan.core.session.LiveDataSession
import com.suzukiscan.core.session.LiveDataSource
import com.suzukiscan.core.session.newSessionId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
) {
    private val _fields = MutableStateFlow(registry.fields)
    val fields: StateFlow<List<FieldDefinition>> = _fields.asStateFlow()

    private val _values = MutableStateFlow<Map<String, Double>>(emptyMap())
    val values: StateFlow<Map<String, Double>> = _values.asStateFlow()

    private val _peaks = MutableStateFlow<Map<String, Double>>(emptyMap())
    val peaks: StateFlow<Map<String, Double>> = _peaks.asStateFlow()

    private val _isLogging = MutableStateFlow(false)
    val isLogging: StateFlow<Boolean> = _isLogging.asStateFlow()

    /** Identifies the current/last logging session (e.g. "20260825-101532"), used to name export files. */
    private val _sessionId = MutableStateFlow<String?>(null)
    val sessionId: StateFlow<String?> = _sessionId.asStateFlow()

    private var pollJob: Job? = null
    private var session = LiveDataSession(source, fieldsProvider = { _fields.value.filter { it.enabled || it.recordEnabled } })
    private var currentIoLog = (source as? Elm327LiveDataSource)?.ioLog

    fun start() {
        if (pollJob != null) return
        pollJob = scope.launch {
            session.readings().collect { reading ->
                _values.value = _values.value + (reading.fieldId to reading.value)
                autoScaleGaugeMax(reading.fieldId, reading.value)
                if (_isLogging.value && registry.get(reading.fieldId)?.recordEnabled == true) {
                    recorder.record(reading)
                    _peaks.value = _peaks.value + (reading.fieldId to (recorder.peak(reading.fieldId) ?: reading.value))
                }
            }
        }
    }

    /** Ratchets a field's gauge max up (never down) to the highest value ever recorded for it,
     * rounded to a nice number — persisted so future runs start already scaled correctly. */
    private fun autoScaleGaugeMax(id: String, value: Double) {
        val field = registry.get(id) ?: return
        if (value <= field.gaugeMax) return
        setGaugeMax(id, niceScaleMax(value))
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    /** Swaps the live-data backend (e.g. simulated -> real ELM327 hardware) and restarts polling if running. */
    fun useSource(newSource: LiveDataSource) {
        val wasRunning = pollJob != null
        stop()
        source = newSource
        session = LiveDataSession(source, fieldsProvider = { _fields.value.filter { it.enabled || it.recordEnabled } })
        currentIoLog = (newSource as? Elm327LiveDataSource)?.ioLog
        _values.value = emptyMap()
        if (wasRunning) start()
    }


    fun setFieldEnabled(id: String, enabled: Boolean) {
        registry.setEnabled(id, enabled)
        _fields.value = registry.fields
        persist()
    }

    fun setRecordEnabled(id: String, recordEnabled: Boolean) {
        registry.setRecordEnabled(id, recordEnabled)
        _fields.value = registry.fields
        persist()
    }

    fun setThresholds(id: String, cautionThreshold: Double?, warningThreshold: Double?) {
        registry.setThresholds(id, cautionThreshold, warningThreshold)
        _fields.value = registry.fields
        persist()
    }

    fun setGaugeMax(id: String, gaugeMax: Double) {
        registry.setGaugeMax(id, gaugeMax)
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

    /** Re-syncs [fields] after external registry changes (e.g. loading persisted config from disk). */
    fun refreshFields() {
        _fields.value = registry.fields
    }

    fun startLogging() {
        recorder.clear()
        _peaks.value = emptyMap()
        _sessionId.value = newSessionId()
        _isLogging.value = true
    }

    fun stopLogging() {
        _isLogging.value = false
    }

    fun exportCsv(): String = recorder.toCsv(
        labelFor = { id -> registry.get(id)?.label ?: id },
        unitFor = { id -> registry.get(id)?.unit ?: "" },
    )

    /** Unique per-session file name so exporting multiple sessions doesn't overwrite each other. */
    fun exportCsvFileName(): String = "suzuki-scan-log-${_sessionId.value ?: newSessionId()}.csv"
    fun exportLogFileName(): String = "suzuki-scan-connection-log-${_sessionId.value ?: newSessionId()}.txt"

    /** Raw ELM327 AT-command/response trace for the active hardware connection (if any) —
     * useful for diagnosing the first few real vehicle connections, safe to ignore otherwise. */
    fun exportIoLog(): String = currentIoLog?.toText() ?: "No hardware connection active — nothing logged yet."
}
