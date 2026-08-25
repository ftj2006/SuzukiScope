package com.suzukiscan.core.session

import com.suzukiscan.core.elm327.Elm327Client
import com.suzukiscan.core.elm327.Elm327Protocol
import com.suzukiscan.core.field.FieldCodec
import com.suzukiscan.core.field.FieldDefinition
import com.suzukiscan.core.log.Elm327IoLog
import com.suzukiscan.core.log.IoDirection
import com.suzukiscan.core.transport.Transport

/**
 * Real hardware [LiveDataSource]: talks to an ELM327 adapter over any [Transport]
 * (Bluetooth SPP, Wi-Fi, serial) using the diagnostic session parameters in each
 * field's [com.suzukiscan.core.field.RequestSpec]/[com.suzukiscan.core.field.DecodeSpec].
 *
 * NOTE: does not yet manage a persistent KWP2000 session (StartSession + periodic
 * TesterPresent) — each poll currently re-addresses and sends one request/response.
 * That's sufficient for CAN-UDS reads and many K-Line ReadDataByLocalIdentifier
 * requests but will need session keep-alive added for modules that require it.
 */
class Elm327LiveDataSource(
    private val transport: Transport,
    private val protocol: Elm327Protocol = Elm327Protocol.KWP_FAST,
    /** Raw AT-command/response trace — useful while validating the first real vehicle connections. */
    val ioLog: Elm327IoLog = Elm327IoLog(),
) : LiveDataSource {
    /** Exposed so other features (e.g. DTC read/clear) can share this connection's client. */
    val client = Elm327Client(transport, ioLog)
    private var initialised = false
    private var lastHeader: List<Int>? = null

    suspend fun connect() {
        client.connect()
        client.reset()
        client.initProtocol(protocol)
        initialised = true
    }

    suspend fun disconnect() {
        client.disconnect()
        initialised = false
    }

    override suspend fun poll(field: FieldDefinition): Double {
        check(initialised) { "Elm327LiveDataSource.connect() must be called before polling" }

        try {
            val header = listOf(if (field.request.isFunctionalAddress) 0xC0 else 0x80, field.request.targetAddress, 0xF1)
            if (header != lastHeader) {
                client.setHeader(header)
                lastHeader = header
            }

            val requestPayload = FieldCodec.buildRequestPayload(field.request)
            val rawAnswer = client.requestHex(requestPayload)

            val prefix = field.request.responsePrefixBytes
            val suffix = field.request.responseSuffixBytes
            require(rawAnswer.size > prefix + suffix) {
                "Response too short to strip ${prefix + suffix} framing bytes: ${rawAnswer.size} bytes for field ${field.id}"
            }
            val dataPayload = rawAnswer.copyOfRange(prefix, rawAnswer.size - suffix)
            return FieldCodec.decode(field.decode, dataPayload)
        } catch (e: Exception) {
            ioLog.append(IoDirection.ERROR, "poll '${field.id}' failed: ${e.message}")
            throw e
        }
    }
}
