package com.suzukiscope.core.protocol.sdl

/**
 * Legacy Suzuki "SDL" serial protocol (pin #9, ALDL-like 5V line), ported from
 * com.malykh.szviewer.common.sdl.SDL.
 *
 * Frame layout: [ecuType:4 | mode:4], [length], [data...], [checksum].
 */
data class SdlFrame(val ecuType: Byte, val mode: Byte, val data: ByteArray = ByteArray(0)) {

    fun encode(): ByteArray {
        val size = 3 + data.size
        val out = ByteArray(size)
        out[0] = (((ecuType.toInt() and 0xF) shl 4) or (mode.toInt() and 0xF)).toByte()
        out[1] = size.toByte()
        data.copyInto(out, destinationOffset = 2)
        var sum = 0
        for (i in 0 until size - 1) sum += out[i].toInt() and 0xFF
        out[size - 1] = ((256 - (sum and 0xFF)) and 0xFF).toByte()
        return out
    }

    companion object {
        const val BAUD_SLOW = 5
        const val BAUD_NORMAL = 8192
        const val INTER_REQUEST_MS = 30
    }
}
