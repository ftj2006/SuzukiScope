package com.suzukiscan.core.field

/** Decodes a raw diagnostic response payload into a physical value, per [DecodeSpec]. */
object FieldCodec {

    fun decode(spec: DecodeSpec, payload: ByteArray): Double {
        require(payload.size >= spec.skipBytes + spec.byteLength) {
            "Payload too short: need ${spec.skipBytes + spec.byteLength} bytes, got ${payload.size}"
        }
        var raw = 0L
        for (i in 0 until spec.byteLength) {
            raw = (raw shl 8) or (payload[spec.skipBytes + i].toLong() and 0xFF)
        }
        if (spec.signed) {
            val bits = spec.byteLength * 8
            val signBit = 1L shl (bits - 1)
            if (raw and signBit != 0L) raw -= (1L shl bits)
        }
        return raw * spec.scale + spec.offset
    }

    /** Builds the request byte sequence (mode + params) to send for this field, per [RequestSpec]. */
    fun buildRequestPayload(spec: RequestSpec): ByteArray =
        byteArrayOf(spec.mode.toByte()) + spec.params.map { it.toByte() }.toByteArray()
}
