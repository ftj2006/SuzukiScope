package com.suzukiscan.core.elm327

import com.suzukiscan.core.log.Elm327IoLog
import com.suzukiscan.core.log.IoDirection
import com.suzukiscan.core.log.describeError
import com.suzukiscan.core.transport.Transport

/** Whether the adapter should be initialised for K-Line (KWP2000) or CAN traffic. */
enum class Elm327Protocol(val atSpValue: String) {
    /** ISO 14230-4 KWP (fast init). SZ Viewer's "sdlmod" K-Line protocol. */
    KWP_FAST("5"),

    /** ISO 15765-4, 11-bit ID, 500 kbaud — typical Suzuki CAN-UDS. */
    CAN_11BIT_500K("6"),
}

/**
 * Minimal ELM327 AT-command client: sends commands, reads until the '>' prompt, strips echo.
 * Ported understanding from com.malykh.szviewer.common.elm327 (init/request/answer packages) —
 * this is a from-scratch reimplementation of the AT command envelope, not a decompiled port.
 */
class Elm327Client(private val transport: Transport, private val ioLog: Elm327IoLog? = null) {

    suspend fun connect() = transport.connect()
    suspend fun disconnect() = transport.disconnect()

    /** Sends a raw AT/hex command, terminated with CR, and returns the trimmed text response. */
    suspend fun sendCommand(command: String, timeoutMs: Long = 2000): String {
        val cmd = command.trim()
        ioLog?.append(IoDirection.SENT, cmd)
        try {
            transport.write((cmd + "\r").encodeToByteArray())
        } catch (e: Exception) {
            ioLog?.append(IoDirection.ERROR, "write failed for '$cmd': ${describeError(e)}")
            throw e
        }
        val raw = try {
            transport.readUntil('>'.code.toByte(), timeoutMs)
        } catch (e: Exception) {
            ioLog?.append(IoDirection.ERROR, "no response to '$cmd' within ${timeoutMs}ms: ${describeError(e)}")
            throw e
        }
        val rawText = raw.decodeToString()
        ioLog?.append(IoDirection.RECEIVED, rawText.replace("\r", "\\r").replace("\n", "\\n"))
        return cleanResponse(rawText)
    }

    /** Standard reset + quiet init sequence. */
    suspend fun reset() {
        sendCommand("ATZ", timeoutMs = 3000)
        sendCommand("ATE0") // echo off
        sendCommand("ATL0") // linefeeds off
        sendCommand("ATS0") // spaces off (we still parse hex-pair by hex-pair regardless)
    }

    suspend fun initProtocol(protocol: Elm327Protocol) {
        sendCommand("ATSP${protocol.atSpValue}")
    }

    /** ISO 14230/KWP: set the physical/functional target header ELM327 will use for the next request. */
    suspend fun setHeader(headerBytes: List<Int>) {
        sendCommand("ATSH" + headerBytes.joinToString("") { "%02X".format(it) })
    }

    /** Sends a hex payload (mode + params) and returns the raw hex bytes of the answer (post-cleanup). */
    suspend fun requestHex(payload: ByteArray): ByteArray {
        val hex = payload.joinToString("") { "%02X".format(it) }
        val response = sendCommand(hex)
        return parseHexBytes(response)
    }

    companion object {
        /** Strips echo of the command, the '>' prompt, whitespace, and "SEARCHING..."/error noise. */
        internal fun cleanResponse(raw: String): String =
            raw.replace(">", "")
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("SEARCHING") }
                .joinToString(" ")
                .trim()

        /** Parses whitespace-separated hex byte pairs (ignoring any trailing non-hex noise). */
        internal fun parseHexBytes(text: String): ByteArray {
            val hexOnly = text.filter { it.isDigit() || it.uppercaseChar() in 'A'..'F' }
            require(hexOnly.length % 2 == 0) { "Odd number of hex digits in response: '$text'" }
            return ByteArray(hexOnly.length / 2) { i ->
                hexOnly.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }
    }
}
