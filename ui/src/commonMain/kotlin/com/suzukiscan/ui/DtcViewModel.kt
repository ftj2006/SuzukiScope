package com.suzukiscan.ui

import com.suzukiscan.core.dtc.DtcCode
import com.suzukiscan.core.dtc.DtcDescriptions
import com.suzukiscan.core.dtc.DtcSession
import com.suzukiscan.core.elm327.Elm327Client
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Drives the "read/clear codes" screen. Reuses whatever Elm327Client the dashboard connected. */
class DtcViewModel {
    private var session: DtcSession? = null

    private val _codes = MutableStateFlow<List<DtcCode>>(emptyList())
    val codes: StateFlow<List<DtcCode>> = _codes.asStateFlow()

    private val _status = MutableStateFlow("Not connected")
    val status: StateFlow<String> = _status.asStateFlow()

    fun attachClient(client: Elm327Client) {
        session = DtcSession(client, DtcDescriptions::describe)
        _status.value = "Ready"
    }

    suspend fun readCodes(targetAddress: Int, isFunctionalAddress: Boolean = false, isCan: Boolean = false) {
        val s = session ?: run { _status.value = "Connect to an adapter first"; return }
        _status.value = "Reading codes..."
        try {
            _codes.value = if (isCan) {
                s.readDtcs(targetAddress, isFunctionalAddress, responsePrefixBytes = 0, responseSuffixBytes = 0)
            } else {
                s.readDtcs(targetAddress, isFunctionalAddress)
            }
            _status.value = if (_codes.value.isEmpty()) "No codes found" else "${_codes.value.size} code(s) found"
        } catch (e: Exception) {
            _status.value = "Read failed: ${e.message}"
        }
    }

    suspend fun clearCodes(targetAddress: Int, isFunctionalAddress: Boolean = true, isCan: Boolean = false) {
        val s = session ?: run { _status.value = "Connect to an adapter first"; return }
        _status.value = "Clearing codes..."
        try {
            val ok = if (isCan) {
                s.clearDtcs(targetAddress, isFunctionalAddress, responsePrefixBytes = 0, responseSuffixBytes = 0)
            } else {
                s.clearDtcs(targetAddress, isFunctionalAddress)
            }
            _status.value = if (ok) "Codes cleared" else "Clear not acknowledged by module"
            if (ok) _codes.value = emptyList()
        } catch (e: Exception) {
            _status.value = "Clear failed: ${e.message}"
        }
    }
}
