package com.suzukiscan.core.session

import com.suzukiscan.core.elm327.Elm327Client
import com.suzukiscan.core.elm327.Elm327Protocol
import com.suzukiscan.core.field.FieldCodec
import com.suzukiscan.core.field.FieldDefinition
import com.suzukiscan.core.log.Elm327IoLog
import com.suzukiscan.core.log.IoDirection
import com.suzukiscan.core.log.describeError
import com.suzukiscan.core.transport.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Real hardware [LiveDataSource]: talks to an ELM327 adapter over any [Transport]
 * (Bluetooth SPP, Wi-Fi, serial) using the diagnostic session parameters in each
 * field's [com.suzukiscan.core.field.RequestSpec]/[com.suzukiscan.core.field.DecodeSpec].
 *
 * Maintains the K-Line (KWP2000) diagnostic session with a periodic TesterPresent (mode 0x3E)
 * keep-alive (see [startKeepAlive]) — CAN-UDS doesn't need this since each read is stateless.
 */
class Elm327LiveDataSource(
    private val transport: Transport,
    val protocol: Elm327Protocol = Elm327Protocol.KWP_FAST,
    /** Raw AT-command/response trace — useful while validating the first real vehicle connections. */
    val ioLog: Elm327IoLog = Elm327IoLog(),
) : LiveDataSource {
    val transportName: String get() = transport.name
    /** Exposed so other features (e.g. DTC read/clear) can share this connection's client. */
    val client = Elm327Client(transport, ioLog)
    private var initialised = false
    private var lastHeader: List<Int>? = null
    private var canConfiguration: Int? = null
    private var kwpInitialisedTarget: Int? = null
    private var keepAliveJob: Job? = null

    // Several fields (e.g. the default oil/water temp, boost, rpm, throttle, speed set) share an
    // identical RequestSpec (same target/mode/params) and only differ in where they decode their
    // value from within the shared response payload. Without this cache, each field would issue
    // its own round trip for what is really the same request, multiplying real-hardware latency
    // by the number of fields sharing it. A failed request is cached too (as the exception it
    // threw) so a timeout on the shared request fails fast for every field in the same cycle
    // instead of each one waiting out its own full timeout in turn. Cleared once per polling
    // cycle via [beginCycle].
    private val requestCache = HashMap<String, Result<ByteArray>>()

    override fun beginCycle() {
        requestCache.clear()
    }

    suspend fun connect() {
        ioLog.append(IoDirection.SENT, "opening transport ${transport.name}")
        try {
            client.connect()
        } catch (e: Exception) {
            ioLog.append(IoDirection.ERROR, "transport connect failed: ${describeError(e)}")
            throw e
        }
        ioLog.append(IoDirection.RECEIVED, "transport connected, sending ELM327 reset")
        try {
            client.reset()
            client.initProtocol(protocol)
        } catch (e: Exception) {
            ioLog.append(IoDirection.ERROR, "ELM327 init failed: ${describeError(e)}")
            throw e
        }
        ioLog.append(IoDirection.RECEIVED, "ELM327 initialised for $protocol")
        initialised = true
    }

    suspend fun disconnect() {
        stopKeepAlive()
        client.disconnect()
        initialised = false
    }

    /**
     * Starts sending TesterPresent (mode 0x3E) every [intervalMs] to keep the K-Line diagnostic
     * session alive between polls. No-op for CAN-UDS, which is stateless per-read. Safe to call
     * repeatedly (restarts); safe to run alongside [poll] since [Elm327Client.sendCommand] is
     * mutex-guarded.
     */
    fun startKeepAlive(scope: CoroutineScope, intervalMs: Long = 2000) {
        if (protocol != Elm327Protocol.KWP_FAST) return
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            while (isActive) {
                delay(intervalMs)
                if (!initialised) continue
                try {
                    client.sendCommand("3E")
                } catch (e: Exception) {
                    ioLog.append(IoDirection.ERROR, "keep-alive failed: ${describeError(e)}")
                }
            }
        }
    }

    fun stopKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = null
    }

    override suspend fun poll(field: FieldDefinition): Double {
        check(initialised) { "Elm327LiveDataSource.connect() must be called before polling" }

        try {
            if (protocol == Elm327Protocol.CAN_11BIT_500K) {
                val header = listOf(field.request.targetAddress)
                if (header != lastHeader) {
                    val target = if (field.request.isFunctionalAddress) 0x7DF else field.request.targetAddress
                    if (canConfiguration != target) {
                        client.configureCan(target)
                        canConfiguration = target
                    }
                    client.setCanHeader(target)
                    lastHeader = header
                }
            } else {
                val header = listOf(if (field.request.isFunctionalAddress) 0xC0 else 0x80, field.request.targetAddress, 0xF1)
                if (header != lastHeader) {
                    client.setHeader(header)
                    if (kwpInitialisedTarget != field.request.targetAddress) {
                        client.sendCommand("ATST19")
                        client.sendCommand("ATFI", timeoutMs = 1000)
                        kwpInitialisedTarget = field.request.targetAddress
                    }
                    lastHeader = header
                }
            }

            val requestPayload = FieldCodec.buildRequestPayload(field.request)
            val cacheKey = "${field.request.targetAddress}:${field.request.isFunctionalAddress}:" +
                requestPayload.joinToString("") { "%02X".format(it) }
            val rawAnswer = (requestCache[cacheKey] ?: run {
                val result = runCatching { client.requestHex(requestPayload, if (protocol == Elm327Protocol.CAN_11BIT_500K) 500 else 1000) }
                requestCache[cacheKey] = result
                result
            }).getOrThrow()

            val prefix = field.request.responsePrefixBytes
            val suffix = field.request.responseSuffixBytes
            require(rawAnswer.size > prefix + suffix) {
                "Response too short to strip ${prefix + suffix} framing bytes: ${rawAnswer.size} bytes for field ${field.id}"
            }
            val dataPayload = rawAnswer.copyOfRange(prefix, rawAnswer.size - suffix)
            return FieldCodec.decode(field.decode, dataPayload)
        } catch (e: Exception) {
            ioLog.append(IoDirection.ERROR, "poll '${field.id}' failed: ${describeError(e)}")
            throw e
        }
    }
}
