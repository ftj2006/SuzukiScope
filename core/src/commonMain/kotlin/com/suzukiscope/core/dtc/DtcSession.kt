package com.suzukiscope.core.dtc

import com.suzukiscope.core.elm327.Elm327Client
import com.suzukiscope.core.protocol.Mode

/**
 * Reads/clears DTCs on a given K-Line/CAN module address. Suzuki modules use KWP2000
 * ReadDTCByStatus (`18 02 FF 00`) and ClearDiagnosticInformation (`14 FF 00`); the engine ECU
 * can additionally be asked for the generic OBD-II stored/pending/permanent codes (modes 03/07/0A).
 * [responsePrefixBytes]/[responseSuffixBytes] strip the KWP2000 header+checksum framing (3/1,
 * the default) — pass 0/0 for CAN modules, which ELM327 reports without that framing.
 */
class DtcSession(
    private val client: Elm327Client,
    private val describe: (String) -> String? = { null },
    private val log: (String) -> Unit = {},
) {

    private fun header(targetAddress: Int, isFunctionalAddress: Boolean) =
        listOf(if (isFunctionalAddress) 0xC0 else 0x80, targetAddress, 0xF1)

    private suspend fun addressModule(targetAddress: Int, isFunctionalAddress: Boolean, isCan: Boolean) {
        if (!isCan) {
            client.setHeader(header(targetAddress, isFunctionalAddress))
        } else if (isFunctionalAddress) {
            // Broadcast on 7DF but only accept (and flow-control) the engine ECU's reply on 7E8.
            client.configureCan(ENGINE_ECU)
            client.setCanHeader(OBD_FUNCTIONAL)
        } else {
            client.configureCan(targetAddress)
        }
    }

    /** Sends [payload] and returns the positive reply's bytes, or throws [DtcRefusedException] on a 7F reply. */
    private suspend fun request(payload: ByteArray, isCan: Boolean, prefix: Int, suffix: Int): ByteArray {
        val answer = client.requestHex(payload, TIMEOUT_MS, checkReply = isCan)
        val reply = if (answer.size > prefix + suffix) answer.copyOfRange(prefix, answer.size - suffix) else ByteArray(0)
        val service = payload[0].toInt() and 0xFF
        if (reply.isNotEmpty() && reply[0] == NEGATIVE_REPLY) {
            throw DtcRefusedException(reply.getOrNull(2)?.toInt()?.and(0xFF) ?: 0)
        }
        check(reply.isNotEmpty() && (reply[0].toInt() and 0xFF) == service + 0x40) {
            "unexpected reply: " + answer.joinToString("") { "%02X".format(it) }
        }
        return reply
    }

    /** Reads Suzuki DTCs (KWP2000 service 18, all groups) from [targetAddress]. */
    suspend fun readDtcs(
        targetAddress: Int,
        isFunctionalAddress: Boolean = false,
        isCan: Boolean = false,
        responsePrefixBytes: Int = if (isCan) 0 else 3,
        responseSuffixBytes: Int = if (isCan) 0 else 1,
    ): List<DtcCode> {
        addressModule(targetAddress, isFunctionalAddress, isCan)
        val reply = request(READ_ALL_DTCS, isCan, responsePrefixBytes, responseSuffixBytes)
        // 58 <count> then <high> <low> <status> per code.
        val codes = mutableListOf<DtcCode>()
        var i = 2
        while (i + 1 < reply.size) {
            val high = reply[i].toInt() and 0xFF
            val low = reply[i + 1].toInt() and 0xFF
            val status = reply.getOrNull(i + 2)?.toInt()?.and(0xFF)
            if (high != 0 || low != 0) {
                val formatted = formatDtc(high, low)
                codes += DtcCode(formatted, status, describe(formatted))
            }
            i += 3
        }
        return codes
    }

    /** Reads generic OBD-II codes with [mode] (03 stored, 07 pending, 0A permanent). */
    suspend fun readObdDtcs(mode: Byte, targetAddress: Int, isFunctionalAddress: Boolean, isCan: Boolean): List<DtcCode> {
        addressModule(targetAddress, isFunctionalAddress, isCan)
        val reply = request(byteArrayOf(mode), isCan, if (isCan) 0 else 3, if (isCan) 0 else 1)
        // On CAN (ISO 15765-4) a count byte follows the service byte; K-Line pads with 00 00 instead.
        val start = if (isCan) 2 else 1
        val codes = mutableListOf<DtcCode>()
        var i = start
        while (i + 1 < reply.size) {
            val high = reply[i].toInt() and 0xFF
            val low = reply[i + 1].toInt() and 0xFF
            if (high != 0 || low != 0) {
                val formatted = formatDtc(high, low)
                codes += DtcCode(formatted, null, describe(formatted))
            }
            i += 2
        }
        return codes
    }

    /**
     * Reads the engine ECU every way it might answer: Suzuki service 18, then OBD-II modes 03/07/0A,
     * first addressed to the ECU and, if that's refused or silent, broadcast on 7DF.
     */
    suspend fun readEngine(): List<DtcReadResult> {
        val results = ArrayList<DtcReadResult>()
        results += attempt(DtcSource.SUZUKI, "18 02 FF 00 to %03X".format(ENGINE_ECU)) {
            readDtcs(ENGINE_ECU, isCan = true)
        }
        var functional = false
        val obdModes = listOf(
            DtcSource.STORED to Mode.OBDII_REQUEST_DTC,
            DtcSource.PENDING to Mode.OBDII_REQUEST_PENDING_DTC,
            DtcSource.PERMANENT to Mode.OBDII_REQUEST_PERMANENT_DTC,
        )
        for ((index, entry) in obdModes.withIndex()) {
            val (source, mode) = entry
            var result = attempt(source, "%02X to %03X".format(mode, if (functional) OBD_FUNCTIONAL else ENGINE_ECU)) {
                readObdDtcs(mode, ENGINE_ECU, functional, isCan = true)
            }
            if (index == 0 && result.error != null) {
                val broadcast = attempt(source, "%02X to %03X".format(mode, OBD_FUNCTIONAL)) {
                    readObdDtcs(mode, ENGINE_ECU, isFunctionalAddress = true, isCan = true)
                }
                if (broadcast.error == null) {
                    functional = true
                    result = broadcast
                }
            }
            results += result
        }
        return results
    }

    /** Clears engine codes with Suzuki `14 FF 00`, falling back to OBD-II mode 04. Returns what worked. */
    suspend fun clearEngine(): String {
        val suzuki = runCatching { clearDtcs(ENGINE_ECU, isFunctionalAddress = false, isCan = true) }
        log("DTC clear 14 FF 00 to %03X: %s".format(ENGINE_ECU, suzuki.fold({ "accepted" }, ::reason)))
        if (suzuki.isSuccess) return "Codes cleared (Suzuki service 14)"
        for (functional in listOf(false, true)) {
            val obd = runCatching {
                addressModule(ENGINE_ECU, functional, isCan = true)
                request(byteArrayOf(Mode.OBDII_RESET_DTC), isCan = true, prefix = 0, suffix = 0)
            }
            log("DTC clear 04 to %03X: %s".format(if (functional) OBD_FUNCTIONAL else ENGINE_ECU, obd.fold({ "accepted" }, ::reason)))
            if (obd.isSuccess) return "Codes cleared (OBD-II mode 04)"
        }
        return "Clear refused: " + reason(suzuki.exceptionOrNull()!!)
    }

    /** Sends ClearDiagnosticInformation (all groups) to [targetAddress]; throws if refused. */
    suspend fun clearDtcs(
        targetAddress: Int,
        isFunctionalAddress: Boolean = false,
        isCan: Boolean = false,
        responsePrefixBytes: Int = if (isCan) 0 else 3,
        responseSuffixBytes: Int = if (isCan) 0 else 1,
    ): Boolean {
        addressModule(targetAddress, isFunctionalAddress, isCan)
        request(CLEAR_ALL_DTCS, isCan, responsePrefixBytes, responseSuffixBytes)
        return true
    }

    private suspend fun attempt(source: DtcSource, what: String, read: suspend () -> List<DtcCode>): DtcReadResult {
        val result = runCatching { read() }
        val outcome = result.fold({ DtcReadResult(source, it) }, { DtcReadResult(source, emptyList(), reason(it)) })
        log("DTC read $what: " + (outcome.error ?: "${outcome.codes.size} code(s) " + outcome.codes.joinToString(" ") { it.code }))
        return outcome
    }

    companion object {
        const val ENGINE_ECU = 0x7E0
        const val OBD_FUNCTIONAL = 0x7DF
        private const val TIMEOUT_MS = 1500L
        private const val NEGATIVE_REPLY: Byte = 0x7F
        private val READ_ALL_DTCS = byteArrayOf(Mode.READ_DTC, 0x02, 0xFF.toByte(), 0x00)
        private val CLEAR_ALL_DTCS = byteArrayOf(Mode.CLEAR_DTC, 0xFF.toByte(), 0x00)

        /** Short user-facing reason for a failed read/clear. */
        fun reason(e: Throwable): String = when {
            e is DtcRefusedException && e.nrc == 0x78 -> "ECU busy (NRC 78), try again"
            e is DtcRefusedException -> e.message!!
            e.message.orEmpty().contains("NO DATA") -> "no reply"
            else -> e.message ?: e::class.simpleName.orEmpty()
        }
    }
}
