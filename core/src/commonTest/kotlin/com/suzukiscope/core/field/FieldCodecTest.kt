package com.suzukiscope.core.field

import kotlin.test.Test
import kotlin.test.assertEquals

class FieldCodecTest {

    @Test
    fun decodesUnsignedSingleByteWithOffset() {
        val spec = DecodeSpec(skipBytes = 2, byteLength = 1, offset = -40.0)
        val payload = byteArrayOf(0x21, 0x15, 0x50) // mode/DID echo + one data byte (0x50 = 80)
        assertEquals(40.0, FieldCodec.decode(spec, payload))
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
