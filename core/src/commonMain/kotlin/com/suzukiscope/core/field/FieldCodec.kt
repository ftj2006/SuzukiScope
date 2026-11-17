package com.suzukiscope.core.field

/** Decodes a raw diagnostic response payload into a physical value, per [DecodeSpec]. */
object FieldCodec {

    fun decode(spec: DecodeSpec, payload: ByteArray): Double {
        val reference = spec.subtractSkipBytes?.let { rawAt(spec, payload, it) } ?: 0L
        val value = (rawAt(spec, payload, spec.skipBytes) - reference) * spec.scale + spec.offset
        val ratios = spec.gearRatios ?: return value
        val speedAt = spec.gearSpeedSkipBytes ?: return value
        require(payload.size > speedAt) { "Payload too short: need ${speedAt + 1} bytes, got ${payload.size}" }
        return gear(value, (payload[speedAt].toInt() and 0xFF).toDouble(), ratios).toDouble()
    }

    /** 1-based gear whose RPM per km/h is within 6% of the reading, or 0 when stopped, coasting or changing gear. */
    fun gear(rpm: Double, kmh: Double, ratios: List<Double>): Int {
        if (kmh < MIN_GEAR_KMH || rpm < MIN_GEAR_RPM) return 0
        val ratio = rpm / kmh
        val index = ratios.indices.minByOrNull { kotlin.math.abs(ratios[it] - ratio) } ?: return 0
        return if (kotlin.math.abs(ratio - ratios[index]) / ratios[index] < GEAR_TOLERANCE) index + 1 else 0
    }

    private const val MIN_GEAR_KMH = 10.0
    private const val MIN_GEAR_RPM = 900.0
    private const val GEAR_TOLERANCE = 0.06

    private fun rawAt(spec: DecodeSpec, payload: ByteArray, skipBytes: Int): Long {
        require(payload.size >= skipBytes + spec.byteLength) {
            "Payload too short: need ${skipBytes + spec.byteLength} bytes, got ${payload.size}"
        }
        var raw = 0L
        for (i in 0 until spec.byteLength) {
            raw = (raw shl 8) or (payload[skipBytes + i].toLong() and 0xFF)
        }
        spec.bit?.let { return (raw shr it) and ((1L shl spec.bitCount) - 1) }
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
