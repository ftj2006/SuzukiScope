package com.suzukiscope.core.elm327

import com.suzukiscope.core.log.Elm327IoLog
import com.suzukiscope.core.log.IoDirection
import com.suzukiscope.core.log.describeError
import com.suzukiscope.core.transport.Transport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Whether the adapter should be initialised for K-Line (KWP2000) or CAN traffic. */
enum class Elm327Protocol(val atSpValue: String) {
    /** ISO 14230-4 KWP (fast init). SZ Viewer's "sdlmod" K-Line protocol. */
    KWP_FAST("5"),

    /** ISO 15765-4, 11-bit ID, 500 kbaud — typical Suzuki CAN-UDS. */
    CAN_11BIT_500K("6"),
}

/** The bus a field is read over: CAN-UDS fields have no KWP response prefix. */
val com.suzukiscope.core.field.FieldDefinition.busProtocol: Elm327Protocol
    get() = if (request.responsePrefixBytes == 0) Elm327Protocol.CAN_11BIT_500K else Elm327Protocol.KWP_FAST

/**
 * Minimal ELM327 AT-command client: sends commands, reads until the '>' prompt, strips echo.
 * Ported understanding from com.malykh.szviewer.common.elm327 (init/request/answer packages) —
 * this is a from-scratch reimplementation of the AT command envelope, not a decompiled port.
 */
class Elm327Client(private val transport: Transport, private val ioLog: Elm327IoLog? = null) {

    // Guards the transport so a background TesterPresent keep-alive (see Elm327LiveDataSource)
    // can never interleave its bytes with an in-flight poll request/response.
    private val mutex = Mutex()

    suspend fun connect() = transport.connect()
    suspend fun disconnect() = transport.disconnect()

    /** Set once the link itself has failed (write error, connection reset); only a reconnect helps. */
    var transportBroken = false
        private set

    // A reply that missed its timeout still arrives later and would be taken as the next command's answer.
    private var lateReplyPending = false

    /** Sends a raw AT/hex command, terminated with CR, and returns the trimmed text response. */
    suspend fun sendCommand(command: String, timeoutMs: Long = 2000): String = mutex.withLock {
        val cmd = command.trim()
        if (transportBroken) throw IllegalStateException("adapter connection lost")
        if (lateReplyPending) {
            lateReplyPending = false
            drainLateReply()
        }
        ioLog?.append(IoDirection.SENT, cmd)
        try {
            transport.write((cmd + "\r").encodeToByteArray())
        } catch (e: Exception) {
            transportBroken = true
            ioLog?.append(IoDirection.ERROR, "write failed for '$cmd': ${describeError(e)}")
            throw e
        }
        val raw = try {
            transport.readUntil('>'.code.toByte(), timeoutMs)
        } catch (e: Exception) {
            // A cancelled read (polling stopped) leaves the reply in flight, the link itself is fine.
            if (isTimeout(e) || e is kotlinx.coroutines.CancellationException) lateReplyPending = true else transportBroken = true
            ioLog?.append(IoDirection.ERROR, "no response to '$cmd' within ${timeoutMs}ms: ${describeError(e)}")
            throw e
        }
        val rawText = raw.decodeToString()
        // Transports that return a partial read on timeout instead of throwing.
        if (!rawText.endsWith('>')) lateReplyPending = true
        ioLog?.append(IoDirection.RECEIVED, rawText.replace("\r", "\\r").replace("\n", "\\n"))
        cleanResponse(rawText)
    }

    private fun isTimeout(e: Throwable): Boolean =
        e::class.simpleName.orEmpty().contains("Timeout") || e.message.orEmpty().contains("timed out", ignoreCase = true)

    private suspend fun drainLateReply() {
        repeat(5) {
            val stale = try {
                transport.readUntil('>'.code.toByte(), LATE_REPLY_WAIT_MS)
            } catch (e: Exception) {
                return
            }
            if (stale.isEmpty()) return
            ioLog?.append(IoDirection.RECEIVED, "discarded late reply: " + stale.decodeToString().replace("\r", "\\r").replace("\n", "\\n"))
        }
    }

    /** Common ELM327 setup used by the original SZ Viewer before selecting a bus protocol. */
    suspend fun reset() {
        discardPendingOutput()
        sendCommand("ATD")
        sendCommand("ATE0")
        sendCommand("ATL0")
        sendCommand("ATS0")
        sendCommand("ATH0")
        sendCommand("ATD0")
        sendCommand("ATAL")
        sendCommand("ATIB10")
        sendCommand("ATKW0")
        sendCommand("ATSW00")
        sendCommand("ATAT0")
        sendCommand("ATCAF1")
        sendCommand("ATCFC1")
        sendCommand("ATFCSM0")
    }

    /**
     * After a reconnect the adapter can still be finishing the previous session's command (e.g. a
     * bus init) and send its reply late, which shifts every following reply by one. A bare CR
     * interrupts any running operation; whatever arrives before the line goes quiet is dropped.
     */
    private suspend fun discardPendingOutput() = mutex.withLock {
        transport.write("\r".encodeToByteArray())
        repeat(10) {
            val stale = try {
                transport.readUntil('>'.code.toByte(), 400)
            } catch (e: Exception) {
                return@withLock
            }
            if (stale.isEmpty()) return@withLock
            ioLog?.append(IoDirection.RECEIVED, "discarded stale output: " + stale.decodeToString().replace("\r", "\\r"))
        }
    }

    suspend fun initProtocol(protocol: Elm327Protocol) {
        sendCommand("ATTP${protocol.atSpValue}")
    }

    /** ISO 14230/KWP: set the physical/functional target header ELM327 will use for the next request. */
    suspend fun setHeader(headerBytes: List<Int>) {
        sendCommand("ATSH" + headerBytes.joinToString("") { "%02X".format(it) })
    }

    suspend fun setCanHeader(targetAddress: Int) {
        sendCommand("ATSH%03X".format(targetAddress and 0x7FF))
    }

    suspend fun configureCan(targetAddress: Int) {
        val requestId = targetAddress and 0x7FF
        sendCommand("ATSH%03X".format(requestId))
        sendCommand("ATCRA%03X".format((requestId + 8) and 0x7FF))
        sendCommand("ATFCSH%03X".format(requestId))
        sendCommand("ATFCSD300000")
        sendCommand("ATFCSM1")
        // 64 ms end-of-reply wait; ATST08 (32 ms) gave no speed-up and more timeouts.
        sendCommand("ATST10")
    }

    /** Sends a hex payload (mode + params) and returns the raw hex bytes of the answer (post-cleanup). */
    suspend fun requestHex(payload: ByteArray, timeoutMs: Long = 500): ByteArray {
        val hex = payload.joinToString("") { "%02X".format(it) }
        val response = sendCommand(hex, timeoutMs)
        return parseHexBytes(response)
    }

    companion object {
        private const val LATE_REPLY_WAIT_MS = 150L

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
            val response = text.trim()
            require(response != "NO DATA" && response != "ERROR" && response != "?" && response != "STOPPED") {
                "ELM327 rejected request: $response"
            }
            val canChunks = Regex("(?:^|[\\s\\r\\n])[0-9A-Fa-f]:([0-9A-Fa-f]+)(?=\\s|$)")
                .findAll(response)
                .map { it.groupValues[1] }
                .toList()
            val payload = if (canChunks.isNotEmpty()) canChunks.joinToString("") else response
            val hexOnly = payload.filter { it.isDigit() || it.uppercaseChar() in 'A'..'F' }
            require(hexOnly.length % 2 == 0) { "Odd number of hex digits in response: '$response'" }
            return ByteArray(hexOnly.length / 2) { i ->
                hexOnly.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }
    }
}
