package com.suzukiscope.core.protocol.iso14230

/**
 * KWP2000 frame builder: header byte encodes length/format, followed by target,
 * source, data bytes, and an XOR/checksum trailer, per ISO 14230-2.
 * Ported/derived from com.malykh.szviewer.common.iso14230 framing logic.
 */
object Iso14230Frame {

    private const val FMT_PHYSICAL = 0x80
    private const val FMT_FUNCTIONAL = 0xC0

    /**
     * Builds a full KWP2000 frame with 3-byte header (fmt, target, source) for
     * payloads up to 63 bytes (length encoded in the low 6 bits of fmt).
     */
    fun encode(msg: Msg): ByteArray {
        val payload = byteArrayOf(msg.mode) + msg.params
        require(payload.size <= 0x3F) { "Payload too large for single KWP2000 frame: ${payload.size}" }

        val isFunctional = msg.to is FunctionalKLineAddressCode
        val fmt = ((if (isFunctional) FMT_FUNCTIONAL else FMT_PHYSICAL) or payload.size).toByte()

        val frame = ByteArray(3 + payload.size + 1)
        frame[0] = fmt
        frame[1] = msg.to.codeByte()
        frame[2] = msg.from.codeByte()
        payload.copyInto(frame, destinationOffset = 3)
        frame[frame.size - 1] = checksum(frame, frame.size - 1)
        return frame
    }

    /** Simple 8-bit sum checksum (mod 256) used by ISO 14230 K-Line frames. */
    private fun checksum(frame: ByteArray, length: Int): Byte {
        var sum = 0
        for (i in 0 until length) sum += frame[i].toInt() and 0xFF
        return (sum and 0xFF).toByte()
    }

    /** Validates the trailing checksum byte of a received frame. */
    fun verifyChecksum(frame: ByteArray): Boolean {
        if (frame.isEmpty()) return false
        val expected = checksum(frame, frame.size - 1)
        return expected == frame.last()
    }
}
