package com.suzukiscope.ui

import com.suzukiscope.core.dtc.DtcCode
import com.suzukiscope.core.dtc.DtcDescriptions
import com.suzukiscope.core.dtc.DtcReadResult
import com.suzukiscope.core.dtc.DtcRefusedException
import com.suzukiscope.core.dtc.DtcSession
import com.suzukiscope.core.elm327.Elm327Client
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Drives the fault codes screen. Reuses whatever Elm327Client the dashboard connected. */
class DtcViewModel(
    /** Called whenever [probeModules] finds a new set of unresponsive modules, so callers can
     * persist it (platform-specific file I/O lives outside this commonMain class). */
    private val persistUnresponsiveModules: (Set<String>) -> Unit = {},
    /** Gives DTC requests the bus to themselves (live polling paused); the argument is a watchdog heartbeat. */
    private val busAccess: suspend (suspend (heartbeat: () -> Unit) -> Unit) -> Unit = { it {} },
    /** Saves an engine snapshot with the given file-name suffix (bus already paused); returns the file name. */
    private val snapshot: suspend (suffix: String) -> String = { error("snapshots are not available") },
) {
    private var session: DtcSession? = null
    private var ioLog: (String) -> Unit = {}
    private var heartbeat: () -> Unit = {}

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Engine results per read method; null until the engine has been read this session. */
    private val _engineResults = MutableStateFlow<List<DtcReadResult>?>(null)
    val engineResults: StateFlow<List<DtcReadResult>?> = _engineResults.asStateFlow()

    private val _engineStatus = MutableStateFlow("Not read yet")
    val engineStatus: StateFlow<String> = _engineStatus.asStateFlow()

    private val _codes = MutableStateFlow<List<DtcCode>>(emptyList())
    val codes: StateFlow<List<DtcCode>> = _codes.asStateFlow()

    private val _status = MutableStateFlow("Not connected")
    val status: StateFlow<String> = _status.asStateFlow()

    /** Module keys (see [moduleKey]) confirmed not to respond on this vehicle, so the module
     * picker can hide them by default without losing them for other vehicle variants. */
    private val _unresponsiveModules = MutableStateFlow<Set<String>>(emptySet())
    val unresponsiveModules: StateFlow<Set<String>> = _unresponsiveModules.asStateFlow()

    /** Progress/result text for [probeModules]; null when no pass is running/just finished. */
    private val _probeStatus = MutableStateFlow<String?>(null)
    val probeStatus: StateFlow<String?> = _probeStatus.asStateFlow()

    fun attachClient(client: Elm327Client, log: (String) -> Unit = {}) {
        ioLog = log
        session = DtcSession(client, DtcDescriptions::describe) { line -> ioLog(line); heartbeat() }
        _status.value = "Ready"
    }

    /** Loads previously-persisted unresponsive module keys, if any. */
    fun loadUnresponsiveModules(keys: Set<String>) {
        _unresponsiveModules.value = keys
    }

    private suspend fun withSession(onMissing: () -> Unit, block: suspend (DtcSession) -> Unit) {
        val s = session ?: run { onMissing(); return }
        if (_busy.value) return
        _busy.value = true
        try {
            busAccess { beat ->
                heartbeat = beat
                block(s)
            }
        } finally {
            heartbeat = {}
            _busy.value = false
        }
    }

    suspend fun readEngine() = withSession({ _engineStatus.value = "Connect to the adapter first" }) { s ->
        _engineStatus.value = "Reading engine codes..."
        try {
            val results = s.readEngine()
            _engineResults.value = results
            val total = results.sumOf { it.codes.size }
            _engineStatus.value = when {
                results.all { it.error != null } -> "The engine ECU didn't answer any code request"
                total == 0 -> "No codes found"
                else -> "$total code(s) found"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _engineStatus.value = "Read failed: ${DtcSession.reason(e)}"
        }
    }

    /** Snapshots the engine tables, clears, then snapshots again so the odometer, trims and history refresh. */
    suspend fun clearEngine() = withSession({ _engineStatus.value = "Connect to the adapter first" }) { s ->
        _engineStatus.value = "Saving a snapshot before clearing..."
        val before = try {
            snapshot("-before-clear")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _engineStatus.value = "Not cleared: the snapshot before clearing failed (${DtcSession.reason(e)})"
            return@withSession
        }
        try {
            _engineStatus.value = "Clearing engine codes..."
            val outcome = s.clearEngine()
            delay(CLEAR_SETTLE_MS)
            _engineStatus.value = "Saving a snapshot after clearing..."
            val after = runCatching { snapshot("-after-clear") }
            _engineResults.value = s.readEngine()
            _engineStatus.value = "$outcome. Snapshots: $before, " +
                after.fold({ it }, { "after-clear snapshot failed (${DtcSession.reason(it)})" })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _engineStatus.value = "Clear failed: ${DtcSession.reason(e)}"
        }
    }

    suspend fun readCodes(targetAddress: Int, isFunctionalAddress: Boolean = false, isCan: Boolean = false) =
        withSession({ _status.value = "Connect to the adapter first" }) { s ->
            _status.value = "Reading codes..."
            try {
                _codes.value = s.readDtcs(targetAddress, isFunctionalAddress, isCan)
                _status.value = if (_codes.value.isEmpty()) "No codes found" else "${_codes.value.size} code(s) found"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _codes.value = emptyList()
                _status.value = "Read failed: ${DtcSession.reason(e)}"
            }
        }

    /**
     * Attempts a DTC read on every module in [modules] and records which ones answered (a refusal
     * still proves the module is there) so the picker can hide the silent ones by default.
     */
    suspend fun probeModules(modules: List<ModuleOption>) = withSession({ _probeStatus.value = "Connect to the adapter first" }) { s ->
        val updated = _unresponsiveModules.value.toMutableSet()
        for ((index, module) in modules.withIndex()) {
            _probeStatus.value = "Testing module ${index + 1}/${modules.size}: ${module.label}"
            val key = moduleKey(module)
            val responded = try {
                s.readDtcs(module.targetAddress, module.isFunctionalAddress, module.isCan)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: DtcRefusedException) {
                true
            } catch (e: Exception) {
                false
            }
            if (responded) updated.remove(key) else updated.add(key)
        }
        _unresponsiveModules.value = updated
        persistUnresponsiveModules(updated)
        _probeStatus.value = "Done: ${modules.size - updated.size} answered, ${updated.size} did not"
    }

    companion object {
        // Gives the ECU a moment to finish erasing before its tables are read back.
        private const val CLEAR_SETTLE_MS = 1000L
        fun moduleKey(module: ModuleOption): String = "${module.targetAddress}:${module.isCan}"
    }
}
