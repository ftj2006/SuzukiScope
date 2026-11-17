package com.suzukiscope.core.field

import kotlin.test.Test
import kotlin.test.assertEquals

class FieldCodecTest {

    @Test
    fun subtractsReferenceValueForRelativeReadings() {
        // Manifold 84 kPa minus barometric 101 kPa -> -0.17 bar (vacuum at idle).
        val spec = DecodeSpec(skipBytes = 1, byteLength = 1, scale = 0.01, subtractSkipBytes = 0)
        assertEquals(-0.17, FieldCodec.decode(spec, byteArrayOf(101, 84)), 1e-9)
    }

    @Test
    fun decodesUnsignedSingleByteWithOffset() {
        val spec = DecodeSpec(skipBytes = 2, byteLength = 1, offset = -40.0)
        val payload = byteArrayOf(0x21, 0x15, 0x50) // mode/DID echo + one data byte (0x50 = 80)
        assertEquals(40.0, FieldCodec.decode(spec, payload))
    }

    @Test
    fun decodesBitFlagsAndBitGroups() {
        val payload = byteArrayOf(0x61, 0x00, 0b0000_1101)
        assertEquals(1.0, FieldCodec.decode(DecodeSpec(skipBytes = 2, bit = 0), payload))
        assertEquals(0.0, FieldCodec.decode(DecodeSpec(skipBytes = 2, bit = 1), payload))
        assertEquals(3.0, FieldCodec.decode(DecodeSpec(skipBytes = 2, bit = 2, bitCount = 2), payload))
    }

    @Test
    fun decodesGearFromRpmAndSpeed() {
        val spec = DecodeSpec(skipBytes = 0, byteLength = 2, scale = 0.25, gearSpeedSkipBytes = 2, gearRatios = listOf(127.0, 72.5, 53.8, 40.8, 32.3, 28.0))
        fun payload(rpm: Int, kmh: Int) = byteArrayOf(((rpm * 4) shr 8).toByte(), (rpm * 4).toByte(), kmh.toByte())
        assertEquals(5.0, FieldCodec.decode(spec, payload(3634, 113)))
        assertEquals(2.0, FieldCodec.decode(spec, payload(3380, 47)))
        assertEquals(0.0, FieldCodec.decode(spec, payload(800, 0)))
        assertEquals(0.0, FieldCodec.decode(spec, payload(3000, 65)))
    }

    @Test
    fun decodesTwoByteScaledValue() {
        val spec = DecodeSpec(skipBytes = 2, byteLength = 2, scale = 0.25)
        val payload = byteArrayOf(0x21, 0x0C, 0x1F, 0x40) // 0x1F40 = 8000 * 0.25 = 2000
        assertEquals(2000.0, FieldCodec.decode(spec, payload))
    }

    @Test
    fun decodesSignedByte() {
        val spec = DecodeSpec(skipBytes = 0, byteLength = 1, signed = true)
        assertEquals(-2.0, FieldCodec.decode(spec, byteArrayOf(0xFE.toByte())))
    }

    @Test
    fun buildsRequestPayload() {
        val spec = RequestSpec(targetAddress = 0x10, mode = 0x21, params = listOf(0x15))
        assertEquals(listOf<Byte>(0x21, 0x15), FieldCodec.buildRequestPayload(spec).toList())
    }
}
