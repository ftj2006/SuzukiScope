package com.suzukiscan.core.dtc

import com.suzukiscan.core.elm327.Elm327Client
import com.suzukiscan.core.protocol.Mode

/**
 * Reads/clears DTCs on a given K-Line/CAN module address, mirroring SZ Viewer's
 * "read codes" / "clear codes" actions. Uses Mode.READ_DTC (0x18) and Mode.CLEAR_DTC (0x14)
 * from the ported KWP2000 service table. [responsePrefixBytes]/[responseSuffixBytes] strip the
 * KWP2000 header+checksum framing (3/1, the default) — pass 0/0 for CAN-UDS modules, which
 * ELM327 reports without that framing.
 */
class DtcSession(
    private val client: Elm327Client,
    private val describe: (String) -> String? = { null },
) {

    private fun header(targetAddress: Int, isFunctionalAddress: Boolean) =
        listOf(if (isFunctionalAddress) 0xC0 else 0x80, targetAddress, 0xF1)

    /** Reads DTCs from [targetAddress]. Returns an empty list if the module reports none. */
    suspend fun readDtcs(
        targetAddress: Int,
        isFunctionalAddress: Boolean = false,
        responsePrefixBytes: Int = 3,
        responseSuffixBytes: Int = 1,
    ): List<DtcCode> {
        client.setHeader(header(targetAddress, isFunctionalAddress))
        val answer = client.requestHex(byteArrayOf(Mode.READ_DTC, 0x00.toByte()))
        val framing = responsePrefixBytes + responseSuffixBytes
        if (answer.size <= framing + 2) return emptyList()
        val payload = answer.copyOfRange(responsePrefixBytes, answer.size - responseSuffixBytes)
        if (payload.size < 2 || payload[0] != Mode.answer(Mode.READ_DTC)) return emptyList()
        val codeBytes = payload.copyOfRange(2, payload.size)
        val codes = mutableListOf<DtcCode>()
        var i = 0
        while (i + 1 < codeBytes.size) {
            val high = codeBytes[i].toInt() and 0xFF
            val low = codeBytes[i + 1].toInt() and 0xFF
            val status = codeBytes.getOrNull(i + 2)?.toInt()?.and(0xFF)
            if (high != 0 || low != 0) {
                val formatted = formatDtc(high, low)
                codes += DtcCode(formatted, status, describe(formatted))
            }
            i += if (status != null) 3 else 2
        }
        return codes
    }

    /** Sends ClearDTC (mode 0x14) to [targetAddress]; returns true if positively acknowledged. */
    suspend fun clearDtcs(
        targetAddress: Int,
        isFunctionalAddress: Boolean = true,
        responsePrefixBytes: Int = 3,
        responseSuffixBytes: Int = 1,
    ): Boolean {
        client.setHeader(header(targetAddress, isFunctionalAddress))
        val answer = client.requestHex(byteArrayOf(Mode.CLEAR_DTC))
        val framing = responsePrefixBytes + responseSuffixBytes
        if (answer.size <= framing + 1) return false
        val payload = answer.copyOfRange(responsePrefixBytes, answer.size - responseSuffixBytes)
        return payload.isNotEmpty() && payload[0] == Mode.answer(Mode.CLEAR_DTC)
    }
}
