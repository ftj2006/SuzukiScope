package com.suzukiscope.core.field

/** Decodes a raw diagnostic response payload into a physical value, per [DecodeSpec]. */
object FieldCodec {

    fun decode(spec: DecodeSpec, payload: ByteArray): Double {
        val reference = spec.subtractSkipBytes?.let { rawAt(spec, payload, it) } ?: 0L
        return (rawAt(spec, payload, spec.skipBytes) - reference) * spec.scale + spec.offset
    }

    private fun rawAt(spec: DecodeSpec, payload: ByteArray, skipBytes: Int): Long {
        require(payload.size >= skipBytes + spec.byteLength) {
            "Payload too short: need ${skipBytes + spec.byteLength} bytes, got ${payload.size}"
        }
        var raw = 0L
        for (i in 0 until spec.byteLength) {
            raw = (raw shl 8) or (payload[skipBytes + i].toLong() and 0xFF)
        }
        if (spec.signed) {
            val bits = spec.byteLength * 8
            val signBit = 1L shl (bits - 1)
            if (raw and signBit != 0L) raw -= (1L shl bits)
        }
        return raw
    }

    /** Builds the request byte sequence (mode + params) to send for this field, per [RequestSpec]. */
    fun buildRequestPayload(spec: RequestSpec): ByteArray =
        byteArrayOf(spec.mode.toByte()) + spec.params.map { it.toByte() }.toByteArray()
}
