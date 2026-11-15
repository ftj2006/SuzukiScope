package com.suzukiscope.core.dtc

/**
 * Formats a 2-byte KWP2000/OBD-II style DTC per SAE J2012: top 2 bits of the high byte select
 * the P/C/B/U prefix, next 2 bits are the first digit, the rest are 3 more hex digits.
 * This is the generic industry format — Suzuki's own proprietary DTC *description* tables
 * (dtc/code/suzuki in the decompiled sources) are a large separate lookup not ported here;
 * codes are shown in this canonical form without a human description.
 */
fun formatDtc(highByte: Int, lowByte: Int): String {
    val prefix = when ((highByte shr 6) and 0x03) {
        0 -> 'P'
        1 -> 'C'
        2 -> 'B'
        else -> 'U'
    }
    val digit1 = (highByte shr 4) and 0x03
    val rest = ((highByte and 0x0F) shl 8) or (lowByte and 0xFF)
    return "$prefix$digit1${rest.toString(16).uppercase().padStart(3, '0')}"
}

data class DtcCode(val code: String, val statusByte: Int? = null, val description: String? = null)

/** How a set of codes was read: Suzuki's own KWP service 18, or the generic OBD-II modes. */
enum class DtcSource(val label: String) {
    SUZUKI("Suzuki codes"),
    STORED("Stored (OBD-II)"),
    PENDING("Pending (OBD-II)"),
    PERMANENT("Permanent (OBD-II)"),
}

/** One read method's outcome: its codes, or [error] when the ECU refused or didn't answer. */
data class DtcReadResult(val source: DtcSource, val codes: List<DtcCode>, val error: String? = null)

class DtcRefusedException(val nrc: Int) : Exception("not supported by this module (NRC %02X)".format(nrc))

/** KWP2000 statusOfDTC: bits 5-6 storage state, bit 7 warning lamp. */
fun describeDtcStatus(status: Int): String {
    val state = when ((status shr 5) and 0x03) {
        3 -> "present now"
        2 -> "intermittent"
        1 -> "stored, not present now"
        else -> "not detected"
    }
    val lamp = if (status and 0x80 != 0) ", warning light on" else ""
    return "$state$lamp (status %02X)".format(status)
}
