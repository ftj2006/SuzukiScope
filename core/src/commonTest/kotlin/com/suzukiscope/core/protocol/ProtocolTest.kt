package com.suzukiscope.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.suzukiscope.core.protocol.iso14230.FunctionalKLineAddressCode
import com.suzukiscope.core.protocol.iso14230.Iso14230Frame
import com.suzukiscope.core.protocol.iso14230.Msg
import com.suzukiscope.core.protocol.iso14230.PhysicalKLineAddressCode
import com.suzukiscope.core.protocol.sdl.SdlFrame
import com.suzukiscope.core.protocol.iso15765.IsoTp
import com.suzukiscope.core.protocol.iso15765.SingleFrame
import com.suzukiscope.core.protocol.iso15765.FirstFrame

class ProtocolTest {

    @Test
    fun modeAnswerBitIsSet() {
        assertEquals(0x50.toByte(), Mode.answer(Mode.START_SESSION))
        assertTrue(Mode.isAnswer(Mode.START_SESSION, 0x50.toByte()))
    }

    @Test
    fun iso14230FrameRoundTripsChecksum() {
        val msg = Msg(
            to = FunctionalKLineAddressCode(0x33),
            from = PhysicalKLineAddressCode(0xF1.toByte()),
            mode = Mode.START_SESSION,
            params = byteArrayOf(0x89.toByte()),
        )
        val frame = Iso14230Frame.encode(msg)
        assertTrue(Iso14230Frame.verifyChecksum(frame))
    }

    @Test
    fun sdlFrameChecksumSumsToZero() {
        val frame = SdlFrame(ecuType = 0x1, mode = 0x2, data = byteArrayOf(0x10)).encode()
        val sum = frame.sumOf { it.toInt() and 0xFF }
        assertEquals(0, sum and 0xFF)
    }

    @Test
    fun isoTpEncodesAndDecodesSingleFrame() {
        val encoded = IsoTp.encodeSingle(byteArrayOf(0x01, 0x0C))
        val decoded = IsoTp.decode(encoded)
        assertTrue(decoded is SingleFrame)
        assertEquals(listOf<Byte>(0x01, 0x0C), (decoded as SingleFrame).data.toList())
    }

    @Test
    fun isoTpEncodesAndDecodesFirstFrame() {
        val encoded = IsoTp.encodeFirst(10, byteArrayOf(1, 2, 3, 4, 5, 6))
        val decoded = IsoTp.decode(encoded)
        assertTrue(decoded is FirstFrame)
        assertEquals(10, (decoded as FirstFrame).totalLength)
    }
}
