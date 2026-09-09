package com.suzukiscope.ui

import com.suzukiscope.core.dtc.DtcCode
import com.suzukiscope.core.dtc.DtcDescriptions
import com.suzukiscope.core.dtc.DtcSession
import com.suzukiscope.core.elm327.Elm327Client
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Drives the "read/clear codes" screen. Reuses whatever Elm327Client the dashboard connected. */
class DtcViewModel(
    /** Called whenever [probeModules] finds a new set of unresponsive modules, so callers can
     * persist it (platform-specific file I/O lives outside this commonMain class). */
    private val persistUnresponsiveModules: (Set<String>) -> Unit = {},
) {
    private var session: DtcSession? = null

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

    fun attachClient(client: Elm327Client) {
        session = DtcSession(client, DtcDescriptions::describe)
        _status.value = "Ready"
    }

    /** Loads previously-persisted unresponsive module keys, if any. */
    fun loadUnresponsiveModules(keys: Set<String>) {
        _unresponsiveModules.value = keys
    }

    suspend fun readCodes(targetAddress: Int, isFunctionalAddress: Boolean = false, isCan: Boolean = false) {
        val s = session ?: run { _status.value = "Connect to an adapter first"; return }
        _status.value = "Reading codes..."
        try {
            _codes.value = s.readDtcs(targetAddress, isFunctionalAddress, isCan)
            _status.value = if (_codes.value.isEmpty()) "No codes found" else "${_codes.value.size} code(s) found"
        } catch (e: Exception) {
            _status.value = "Read failed: ${e.message}"
        }
    }

    suspend fun clearCodes(targetAddress: Int, isFunctionalAddress: Boolean = true, isCan: Boolean = false) {
        val s = session ?: run { _status.value = "Connect to an adapter first"; return }
        _status.value = "Clearing codes..."
        try {
            val ok = s.clearDtcs(targetAddress, isFunctionalAddress, isCan)
            _status.value = if (ok) "Codes cleared" else "Clear not acknowledged by module"
            if (ok) _codes.value = emptyList()
        } catch (e: Exception) {
            _status.value = "Clear failed: ${e.message}"
        }
    }

    /**
     * One-off/automatic diagnostic pass: attempts a DTC read on every module in [modules] and
     * records which ones actually responded vs errored/timed out, so the picker can hide the
     * unresponsive ones by default (a module returning zero codes is a legitimate healthy
     * result, not "unresponsive" \u2014 only an actual failure marks it as such).
     */
    suspend fun probeModules(modules: List<ModuleOption>) {
        val s = session ?: run { _probeStatus.value = "Connect to an adapter first"; return }
        val updated = _unresponsiveModules.value.toMutableSet()
        for ((index, module) in modules.withIndex()) {
            _probeStatus.value = "Testing module ${index + 1}/${modules.size}: ${module.label}"
            val key = moduleKey(module)
            val responded = try {
                s.readDtcs(module.targetAddress, module.isFunctionalAddress, module.isCan)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (responded) updated.remove(key) else updated.add(key)
        }
        _unresponsiveModules.value = updated
        persistUnresponsiveModules(updated)
        _probeStatus.value = "Done: ${modules.size - updated.size} responded, ${updated.size} did not"
    }

    companion object {
        fun moduleKey(module: ModuleOption): String = "${module.targetAddress}:${module.isCan}"
    }
}
