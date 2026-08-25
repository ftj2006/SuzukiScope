package com.suzukiscan.core.dtc

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
