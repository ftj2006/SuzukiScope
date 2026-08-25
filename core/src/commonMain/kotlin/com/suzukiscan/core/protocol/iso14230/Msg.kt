package com.suzukiscan.core.protocol.iso14230

/**
 * K-Line addressing, ported from com.malykh.szviewer.common.iso14230
 * (KLineAddressCode / PhysicalKLineAddressCode / FunctionalKLineAddressCode).
 */
sealed interface KLineAddressCode {
    fun codeByte(): Byte
}

/** A specific ECU address (point-to-point request). */
data class PhysicalKLineAddressCode(private val code: Byte) : KLineAddressCode {
    override fun codeByte(): Byte = code
}

/** A broadcast address targeting all ECUs of a given functional group. */
data class FunctionalKLineAddressCode(private val code: Byte) : KLineAddressCode {
    override fun codeByte(): Byte = code
}

/** Header format variants supported by the KWP2000 framing layer. */
enum class HeaderLengthMode { STANDARD, EXTENDED }
enum class AdditionalLengthMode { NONE, BYTE }
enum class AutoLengthMode { FIXED, VARIABLE }

/**
 * A single KWP2000 message: target/source addressing + body (mode + params),
 * ported from com.malykh.szviewer.common.iso14230.Msg.
 */
data class Msg(
    val to: KLineAddressCode,
    val from: PhysicalKLineAddressCode,
    val mode: Byte,
    val params: ByteArray,
) {
    /** True if [other] is a positive/negative response addressed back to us for our [mode]. */
    fun isAnswerOn(other: Msg, expectedRemote: PhysicalKLineAddressCode? = null): Boolean {
        val fromCompare = expectedRemote?.codeByte() ?: from.codeByte()
        return to.codeByte() == other.from.codeByte() &&
            from.codeByte() == fromCompare &&
            (other.mode == com.suzukiscan.core.protocol.Mode.answer(mode) || other.mode == com.suzukiscan.core.protocol.Mode.ERROR_ANSWER)
    }

    override fun equals(other: Any?): Boolean {
        if (other !is Msg) return false
        return to == other.to && from == other.from && mode == other.mode && params.contentEquals(other.params)
    }

    override fun hashCode(): Int = 31 * (31 * to.hashCode() + from.hashCode()) + mode + params.contentHashCode()
}
