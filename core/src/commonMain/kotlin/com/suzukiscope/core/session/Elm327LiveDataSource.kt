package com.suzukiscope.core.session

import com.suzukiscope.core.elm327.Elm327Client
import com.suzukiscope.core.elm327.Elm327Protocol
import com.suzukiscope.core.elm327.busProtocol
import com.suzukiscope.core.field.FieldCodec
import com.suzukiscope.core.field.FieldDefinition
import com.suzukiscope.core.field.OBD_MODE_CURRENT_DATA
import com.suzukiscope.core.log.Elm327IoLog
import com.suzukiscope.core.log.IoDirection
import com.suzukiscope.core.log.describeError
import com.suzukiscope.core.transport.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Real hardware [LiveDataSource]: talks to an ELM327 adapter over any [Transport]
 * (Bluetooth SPP, Wi-Fi, serial) using the diagnostic session parameters in each
 * field's [com.suzukiscope.core.field.RequestSpec]/[com.suzukiscope.core.field.DecodeSpec].
 *
 * Standard OBD-II fields (mode 01) due in a cycle are fetched together in as few requests as
 * possible; fields whose PIDs the vehicle doesn't support switch to their Suzuki fallback.
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
    val transportBroken: Boolean get() = client.transportBroken
    private var initialised = false
    private var lastHeader: List<Int>? = null
    private var canConfiguration: Int? = null
    private var kwpInitialisedTarget: Int? = null
    private var keepAliveJob: Job? = null

    // Several fields share an identical RequestSpec and only differ in where they decode their
    // value from, so one round trip per request per cycle is reused (failures too, so a timeout
    // fails fast for every field sharing it). Cleared once per polling cycle via [beginCycle].
    private val requestCache = HashMap<String, Result<ByteArray>>()

    // OBD-II PIDs the vehicle reports supporting (null = not queried, e.g. K-Line session).
    private var supportedPids: Set<Int>? = null
    // Some clone adapters reject several PIDs in one request; then each PID is asked for alone.
    private var multiPidRequests = true
    private var cycleFields: List<FieldDefinition> = emptyList()
    // PID -> data bytes for this cycle's batched OBD request(s).
    private val obdValues = HashMap<Int, ByteArray>()
    private var obdBatchFetched = false
    private val loggedFallbacks = HashSet<String>()

    // Performance counters, summarised into the I/O log every PERF_WINDOW_MS.
    private class RequestStats(var count: Int = 0, var totalMs: Long = 0, var maxMs: Long = 0, var failures: Int = 0)
    private val requestStats = LinkedHashMap<String, RequestStats>()
    private var cyclesInWindow = 0
    private var windowStartMs = 0L
    private var lastPlan: String? = null

    override fun beginCycle(fields: List<FieldDefinition>) {
        requestCache.clear()
        obdValues.clear()
        obdBatchFetched = false
        cycleFields = fields
        logPlanIfChanged(fields)
        val now = currentTimeMillis()
        if (windowStartMs == 0L) windowStartMs = now
        cyclesInWindow++
        if (now - windowStartMs >= PERF_WINDOW_MS) logPerformance(now)
    }

    // The adapter is initialised for one bus only.
    override fun canPoll(field: FieldDefinition): Boolean = field.busProtocol == protocol

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
        // No ATAT1: on Suzuki CAN adaptive timing cuts off the tail of slow multi-frame replies.
        if (protocol == Elm327Protocol.CAN_11BIT_500K) querySupportedPids()
    }

    private suspend fun querySupportedPids() {
        val supported = HashSet<Int>()
        try {
            ensureCanHeader(ENGINE_ECU)
            for (base in listOf(0x00, 0x20, 0x40)) {
                if (base != 0x00 && (base !in supported)) break
                val answer = client.requestHex(byteArrayOf(OBD_MODE_CURRENT_DATA.toByte(), base.toByte()), 1000)
                val bitmap = answer.dropWhile { it != 0x41.toByte() }.drop(2).take(4)
                bitmap.forEachIndexed { byteIndex, b ->
                    for (bit in 0 until 8) {
                        if ((b.toInt() shr (7 - bit)) and 1 == 1) supported.add(base + byteIndex * 8 + bit + 1)
                    }
                }
            }
        } catch (e: Exception) {
            ioLog.append(IoDirection.ERROR, "OBD-II supported-PID query failed: ${describeError(e)}")
        }
        supportedPids = supported
        ioLog.append(
            IoDirection.RECEIVED,
            "OBD-II supported PIDs: " + (supported.sorted().joinToString(" ") { "%02X".format(it) }.ifEmpty { "none (fallbacks used)" }),
        )
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
        val effective = resolve(field)
        try {
            return if (effective.request.mode == OBD_MODE_CURRENT_DATA) pollObd(effective) else pollRaw(effective)
        } catch (e: Exception) {
            ioLog.append(IoDirection.ERROR, "poll '${field.id}' failed: ${describeError(e)}")
            throw e
        }
    }

    /** Swaps in the Suzuki fallback request when the vehicle doesn't support the field's OBD-II PIDs. */
    private fun resolve(field: FieldDefinition): FieldDefinition {
        val fallback = field.fallback ?: return field
        if (field.request.mode != OBD_MODE_CURRENT_DATA) return field
        val supported = supportedPids ?: return field
        if (supported.containsAll(field.request.params)) return field
        if (loggedFallbacks.add(field.id)) {
            ioLog.append(IoDirection.RECEIVED, "'${field.id}' uses its fallback request: OBD-II PIDs ${field.request.params.joinToString(" ") { "%02X".format(it) }} not all supported")
        }
        return field.copy(request = fallback.request, decode = fallback.decode)
    }

    private suspend fun pollObd(field: FieldDefinition): Double {
        ensureHeader(field)
        if (!obdBatchFetched) {
            obdBatchFetched = true
            val batch = cycleFields.map(::resolve)
                .filter { it.request.mode == OBD_MODE_CURRENT_DATA && it.request.targetAddress == field.request.targetAddress }
                .flatMap { it.request.params }
            fetchPids((batch + field.request.params).distinct())
        }
        val missing = field.request.params.filter { it !in obdValues }
        if (missing.isNotEmpty()) fetchPids(missing)
        val payload = ArrayList<Byte>()
        payload.add(0x41.toByte())
        for (pid in field.request.params) {
            val data = obdValues[pid] ?: error("vehicle returned no data for OBD-II PID %02X".format(pid))
            payload.add(pid.toByte())
            payload.addAll(data.toList())
        }
        return FieldCodec.decode(field.decode, payload.toByteArray())
    }

    private suspend fun fetchPids(pids: List<Int>) {
        for (chunk in pids.chunked(if (multiPidRequests) MAX_PIDS_PER_REQUEST else 1)) {
            val answer = try {
                timedRequest(byteArrayOf(OBD_MODE_CURRENT_DATA.toByte()) + chunk.map { it.toByte() }.toByteArray(), 500)
            } catch (e: Exception) {
                if (chunk.size > 1 && multiPidRequests) {
                    multiPidRequests = false
                    ioLog.append(IoDirection.ERROR, "multi-PID request rejected (${describeError(e)}); asking for one PID per request from now on")
                    fetchPids(chunk)
                    continue
                }
                continue
            }
            parseObdAnswer(answer)
        }
    }

    private fun parseObdAnswer(answer: ByteArray) {
        var i = answer.indexOf(0x41.toByte())
        if (i < 0) return
        i++
        while (i < answer.size) {
            val pid = answer[i].toInt() and 0xFF
            val length = PID_DATA_LENGTHS[pid]
            if (length == null) {
                ioLog.append(IoDirection.ERROR, "unknown OBD-II PID %02X in reply; rest of reply ignored".format(pid))
                return
            }
            if (i + 1 + length > answer.size) return
            obdValues[pid] = answer.copyOfRange(i + 1, i + 1 + length)
            i += 1 + length
        }
    }

    private suspend fun pollRaw(field: FieldDefinition): Double {
        ensureHeader(field)
        val requestPayload = FieldCodec.buildRequestPayload(field.request)
        val cacheKey = "${field.request.targetAddress}:${field.request.isFunctionalAddress}:" +
            requestPayload.joinToString("") { "%02X".format(it) }
        val rawAnswer = (requestCache[cacheKey] ?: run {
            // The 210-byte 21 00 reply sometimes takes 500-800 ms; discarding it froze the gauges for longer.
            val result = runCatching { timedRequest(requestPayload, if (protocol == Elm327Protocol.CAN_11BIT_500K) 800 else 1000) }
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
    }

    /**
     * One-off probe of every engine ECU local data table (`21 00`-`21 FF`), logging each one's
     * reply size and time, to look for a table smaller than `21 00` that could be polled faster.
     * Returns a one-line summary. Only meaningful on CAN.
     */
    suspend fun probeLocalTables(onProgress: (index: Int, total: Int) -> Unit): String {
        check(protocol == Elm327Protocol.CAN_11BIT_500K) { "Table probe needs a CAN connection" }
        ensureCanHeader(ENGINE_ECU)
        ioLog.append(IoDirection.RECEIVED, "TABLE PROBE start: ECU %03X, 21 00-21 FF".format(ENGINE_ECU))
        val found = ArrayList<String>()
        var refused = 0
        var silent = 0
        for (id in 0x00..0xFF) {
            onProgress(id + 1, 256)
            if (client.transportBroken) break
            val start = currentTimeMillis()
            val result = runCatching { client.requestHex(byteArrayOf(0x21, id.toByte()), 1500) }
            val ms = currentTimeMillis() - start
            val answer = result.getOrNull()
            val line = when {
                answer == null -> { silent++; "no reply (${result.exceptionOrNull()?.let(::describeError)})" }
                answer.size >= 3 && answer[0] == 0x7F.toByte() -> { refused++; "refused, NRC %02X".format(answer[2].toInt() and 0xFF) }
                answer.size >= 2 && answer[0] == 0x61.toByte() -> {
                    val dataBytes = answer.size - 2
                    found += "21%02X=%dB/%dms".format(id, dataBytes, ms)
                    "OK $dataBytes data bytes: " + answer.joinToString("") { "%02X".format(it) }
                }
                else -> "unexpected: " + answer.joinToString("") { "%02X".format(it) }
            }
            ioLog.append(IoDirection.RECEIVED, "TABLE PROBE 21%02X %d ms: %s".format(id, ms, line))
            delay(50)
        }
        val summary = "${found.size} table(s) answered, $refused refused, $silent no reply" +
            if (found.isEmpty()) "" else ": " + found.joinToString(" ")
        ioLog.append(IoDirection.RECEIVED, "TABLE PROBE done: $summary")
        return summary
    }

    private suspend fun ensureHeader(field: FieldDefinition) {
        if (protocol == Elm327Protocol.CAN_11BIT_500K) {
            ensureCanHeader(if (field.request.isFunctionalAddress) 0x7DF else field.request.targetAddress)
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
    }

    private suspend fun ensureCanHeader(target: Int) {
        val header = listOf(target)
        if (header == lastHeader) return
        if (canConfiguration != target) {
            client.configureCan(target)
            canConfiguration = target
        }
        client.setCanHeader(target)
        lastHeader = header
    }

    private suspend fun timedRequest(payload: ByteArray, timeoutMs: Long): ByteArray {
        val key = payload.joinToString("") { "%02X".format(it) }
        val stats = requestStats.getOrPut(key) { RequestStats() }
        val start = currentTimeMillis()
        try {
            return client.requestHex(payload, timeoutMs)
        } catch (e: Exception) {
            stats.failures++
            throw e
        } finally {
            val elapsed = currentTimeMillis() - start
            stats.count++
            stats.totalMs += elapsed
            if (elapsed > stats.maxMs) stats.maxMs = elapsed
        }
    }

    private fun logPerformance(now: Long) {
        val windowMs = now - windowStartMs
        val perCycle = if (cyclesInWindow > 0) windowMs / cyclesInWindow else 0
        val requests = requestStats.entries.joinToString("; ") { (key, s) ->
            "$key ${s.count}x avg ${if (s.count > 0) s.totalMs / s.count else 0} ms max ${s.maxMs} ms" +
                (if (s.failures > 0) " fail ${s.failures}" else "")
        }
        ioLog.append(
            IoDirection.RECEIVED,
            "PERF ${windowMs / 1000.0}s: $cyclesInWindow cycles (avg $perCycle ms/cycle, " +
                "multi-PID ${if (multiPidRequests) "on" else "off"}); requests: ${requests.ifEmpty { "none" }}",
        )
        requestStats.clear()
        cyclesInWindow = 0
        windowStartMs = now
    }

    private fun logPlanIfChanged(fields: List<FieldDefinition>) {
        if (lastPlan != null) return
        val plan = fields.joinToString(", ") { f ->
            val r = resolve(f)
            val via = if (r.request.mode == OBD_MODE_CURRENT_DATA) {
                "OBD " + r.request.params.joinToString(" ") { "%02X".format(it) }
            } else {
                "%02X %s".format(r.request.mode, r.request.params.joinToString(" ") { "%02X".format(it) })
            }
            "${f.id} [$via]"
        }
        if (plan == lastPlan) return
        lastPlan = plan
        ioLog.append(IoDirection.RECEIVED, "poll plan (full cycle): $plan")
    }

    private companion object {
        const val ENGINE_ECU = 0x7E0
        const val PERF_WINDOW_MS = 10_000L
        // ELM327 accepts up to six PIDs in one mode 01 request on CAN.
        const val MAX_PIDS_PER_REQUEST = 6

        // Data bytes per standard OBD-II mode 01 PID (SAE J1979), needed to split batched replies.
        val PID_DATA_LENGTHS = mapOf(
            0x00 to 4, 0x04 to 1, 0x05 to 1, 0x06 to 1, 0x07 to 1, 0x0A to 1, 0x0B to 1, 0x0C to 2,
            0x0D to 1, 0x0E to 1, 0x0F to 1, 0x10 to 2, 0x11 to 1, 0x1F to 2, 0x20 to 4, 0x2F to 1,
            0x33 to 1, 0x40 to 4, 0x42 to 2, 0x43 to 2, 0x44 to 2, 0x45 to 1, 0x46 to 1, 0x47 to 1,
            0x49 to 1, 0x4C to 1, 0x5C to 1,
        )
    }
}
