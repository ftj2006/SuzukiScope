package com.suzukiscan.core.protocol.iso15765

/**
 * ISO 15765-2 (ISO-TP) segmentation over CAN, ported from
 * com.malykh.szviewer.common.iso15765 (SingleTP/FirstTP/ConsTP/FlowTP).
 */
sealed interface TpFrame

data class SingleFrame(val data: ByteArray) : TpFrame {
    init { require(data.size <= 7) { "Single frame payload must be <= 7 bytes" } }
}

data class FirstFrame(val totalLength: Int, val data: ByteArray) : TpFrame {
    init { require(data.size == 6) { "First frame carries exactly 6 data bytes" } }
}

data class ConsecutiveFrame(val sequenceNumber: Int, val data: ByteArray) : TpFrame {
    init { require(sequenceNumber in 0..15) { "Sequence number is a 4-bit nibble" } }
}

enum class FlowStatus { CONTINUE_TO_SEND, WAIT, OVERFLOW }

data class FlowControlFrame(
    val status: FlowStatus,
    val blockSize: Int,
    val separationTimeMs: Int,
) : TpFrame

/** Encodes/decodes the 8-byte CAN payloads used for ISO-TP framing. */
object IsoTp {

    fun encodeSingle(payload: ByteArray): ByteArray {
        require(payload.size <= 7)
        val frame = ByteArray(8)
        frame[0] = payload.size.toByte() // PCI: 0x0N
        payload.copyInto(frame, destinationOffset = 1)
        return frame
    }

    fun encodeFirst(totalLength: Int, first6Bytes: ByteArray): ByteArray {
        require(first6Bytes.size == 6)
        require(totalLength in 8..4095)
        val frame = ByteArray(8)
        frame[0] = (0x10 or ((totalLength shr 8) and 0x0F)).toByte()
        frame[1] = (totalLength and 0xFF).toByte()
        first6Bytes.copyInto(frame, destinationOffset = 2)
        return frame
    }

    fun encodeConsecutive(sequenceNumber: Int, chunk: ByteArray): ByteArray {
        require(chunk.size <= 7)
        val frame = ByteArray(8) { 0x00.toByte() }
        frame[0] = (0x20 or (sequenceNumber and 0x0F)).toByte()
        chunk.copyInto(frame, destinationOffset = 1)
        return frame
    }

    fun encodeFlowControl(status: FlowStatus, blockSize: Int, separationTimeMs: Int): ByteArray {
        val fs = when (status) {
            FlowStatus.CONTINUE_TO_SEND -> 0x0
            FlowStatus.WAIT -> 0x1
            FlowStatus.OVERFLOW -> 0x2
        }
        return byteArrayOf(
            (0x30 or fs).toByte(),
            blockSize.toByte(),
            separationTimeMs.toByte(),
        )
    }

    /** Parses a raw CAN data payload (up to 8 bytes) into a [TpFrame]. */
    fun decode(frame: ByteArray): TpFrame {
        require(frame.isNotEmpty())
        return when ((frame[0].toInt() and 0xF0) ushr 4) {
            0x0 -> SingleFrame(frame.copyOfRange(1, 1 + (frame[0].toInt() and 0x0F)))
            0x1 -> {
                val length = ((frame[0].toInt() and 0x0F) shl 8) or (frame[1].toInt() and 0xFF)
                FirstFrame(length, frame.copyOfRange(2, 8))
            }
            0x2 -> ConsecutiveFrame(frame[0].toInt() and 0x0F, frame.copyOfRange(1, frame.size))
            0x3 -> {
                val status = when (frame[0].toInt() and 0x0F) {
                    0 -> FlowStatus.CONTINUE_TO_SEND
                    1 -> FlowStatus.WAIT
                    else -> FlowStatus.OVERFLOW
                }
                FlowControlFrame(status, frame.getOrElse(1) { 0 }.toInt() and 0xFF, frame.getOrElse(2) { 0 }.toInt() and 0xFF)
            }
            else -> error("Unknown ISO-TP PCI type in frame ${frame.toList()}")
        }
    }
}
