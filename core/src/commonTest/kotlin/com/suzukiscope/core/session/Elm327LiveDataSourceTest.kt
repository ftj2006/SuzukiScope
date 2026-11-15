package com.suzukiscope.core.session

import com.suzukiscope.core.elm327.Elm327Protocol
import com.suzukiscope.core.field.DecodeSpec
import com.suzukiscope.core.field.FieldDefinition
import com.suzukiscope.core.field.FieldFallback
import com.suzukiscope.core.field.OBD_MODE_CURRENT_DATA
import com.suzukiscope.core.field.RequestSpec
import com.suzukiscope.core.transport.LoopbackTransport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class Elm327LiveDataSourceTest {

    @Test
    fun pollsAndDecodesAKLineFieldOverLoopbackTransport() = runTest {
        // Synthetic example (not a verified real DID) purely to exercise the wire mechanics:
        // 80 10 F1 = KWP2000 header (physical, target 0x10, source 0xF1), 61 15 50 = positive
        // response to mode 0x21 DID 0x15 with one data byte 0x50 (=80 after -40 offset), B7 = checksum.
        val transport = LoopbackTransport { bytes ->
            val cmd = bytes.decodeToString().trim()
            when {
                cmd.startsWith("2115") -> "80 10 F1 61 15 50 B7\r>".encodeToByteArray()
                else -> "OK\r>".encodeToByteArray()
            }
        }
        val source = Elm327LiveDataSource(transport)
        source.connect()

        val oilTemp = FieldDefinition(
            id = "test.field",
            label = "Test Field",
            unit = "\u00B0C",
            request = RequestSpec(targetAddress = 0x10, mode = 0x21, params = listOf(0x15)),
            decode = DecodeSpec(skipBytes = 2, byteLength = 1, offset = -40.0),
        )

        assertEquals(40.0, source.poll(oilTemp))
    }

    @Test
    fun reinitialisesAStuckKLineSession() = runTest {
        val requests = mutableListOf<String>()
        var sessionUp = true
        val transport = LoopbackTransport { bytes ->
            val cmd = bytes.decodeToString().trim()
            requests += cmd
            when {
                cmd == "ATFI" -> { sessionUp = true; "BUS INIT: OK\r>" }
                cmd.startsWith("2115") -> if (sessionUp) "80 10 F1 61 15 50 B7\r>" else "BUS ERROR\r>"
                else -> "OK\r>"
            }.encodeToByteArray()
        }
        val source = Elm327LiveDataSource(transport)
        source.connect()
        val field = FieldDefinition(
            id = "test.field",
            label = "Test Field",
            unit = "",
            request = RequestSpec(targetAddress = 0x10, mode = 0x21, params = listOf(0x15)),
            decode = DecodeSpec(skipBytes = 2, byteLength = 1, offset = -40.0),
        )
        assertEquals(40.0, source.poll(field))

        sessionUp = false
        repeat(Elm327LiveDataSource.KWP_RECOVERY_FAILURES) {
            source.beginCycle(listOf(field))
            runCatching { source.poll(field) }
        }
        requests.clear()
        source.beginCycle(listOf(field))
        assertEquals(40.0, source.poll(field))
        assertEquals(true, "ATFI" in requests)
    }

    @Test
    fun skipsAKLineModuleThatFailsBusInit() = runTest {
        val requests = mutableListOf<String>()
        val transport = LoopbackTransport { bytes ->
            val cmd = bytes.decodeToString().trim()
            requests += cmd
            (if (cmd == "ATFI") "BUS INIT: ERROR\r>" else "OK\r>").encodeToByteArray()
        }
        val source = Elm327LiveDataSource(transport)
        source.connect()
        val field = FieldDefinition(
            id = "test.field",
            label = "Test Field",
            unit = "",
            request = RequestSpec(targetAddress = 0x5A, mode = 0x21, params = listOf(0x08)),
            decode = DecodeSpec(skipBytes = 2, byteLength = 1),
        )
        val first = runCatching { source.poll(field) }.exceptionOrNull()?.message.orEmpty()
        kotlin.test.assertContains(first, "did not answer bus init")
        requests.clear()
        runCatching { source.poll(field) }
        assertEquals(emptyList(), requests)
    }

    private fun canAdapter(requests: MutableList<String>) = LoopbackTransport { bytes ->
        val cmd = bytes.decodeToString().trim()
        requests += cmd
        when (cmd) {
            // Supports 0B 0C 0D 11 and the 21-40 range; 0120 then adds 33.
            "0100" -> "41 00 00 38 80 01\r>"
            "0120" -> "41 20 00 00 20 00\r>"
            "010C0B33" -> "41 0C 1A F8 0B 64 33 54\r>"
            "2100" -> "61 00 2A\r>"
            else -> "OK\r>"
        }.encodeToByteArray()
    }

    @Test
    fun snapshotAnnotatesDecodedTables() = runTest {
        // Replies from the car's 2026-11-10 snapshot.
        val replies = mapOf(
            "2130" to "613001187D00BF5665896D808580847B1D00DA0000C51405040261CDFFFFFFFFFFFFFFFFFFFFFF0000",
            "2134" to "613418F50217E10117B70216DF0116DA00C85C00",
            "2135" to "61350105042000000000000000000000C85B5580888085BC868A63",
            "213A" to "613A0EFF0FFF8DFF",
            "2141" to "61410504000000438087FFFF55",
        )
        val transport = LoopbackTransport { bytes ->
            val cmd = bytes.decodeToString().trim()
            (replies[cmd] ?: if (cmd.startsWith("21")) "7F2112" else "OK").plus("\r>").encodeToByteArray()
        }
        val source = Elm327LiveDataSource(transport, Elm327Protocol.CAN_11BIT_500K)
        source.connect()
        val snapshot = source.readEcuSnapshot {}.text
        kotlin.test.assertContains(snapshot, "(engine starts: 6389, odometer: 51292 km)")
        kotlin.test.assertContains(snapshot, "(record: P0504, odometer stamp 51291 km, baro 85 kPa, trims +0.0% / +6.3% / +0.0% / +3.9%)")
        kotlin.test.assertContains(snapshot, "(record 1: P0504, odometer stamp 50452 km, trims +0.0% / +3.9% / +0.0% / +3.1%)")
        kotlin.test.assertContains(snapshot, "(start log, newest first: 14 15 13 ...; 1 flagged)")
        kotlin.test.assertContains(snapshot, "(stored fault code: P0504)")

        val tables = source.readEcuSnapshot {}.tables
        val summary = com.suzukiscope.core.ecu.EcuTables.summarise(tables)
        assertEquals(6389, summary.engineStarts)
        assertEquals(51292, summary.odometerKm)
        assertEquals(listOf(6.25, 3.90625), summary.learnedTrims)
        assertEquals("P0504", summary.latestFault?.code)
        assertEquals(51291, summary.latestFault?.odometerKm)
        assertEquals(
            listOf("21 35" to 51291, "21 30" to 50452, "21 41" to null),
            com.suzukiscope.core.ecu.EcuTables.faultHistory(tables).map { it.source to it.odometerKm },
        )
        assertEquals(tables.keys, com.suzukiscope.core.ecu.EcuTables.fromText(com.suzukiscope.core.ecu.EcuTables.toText(tables)).keys)

        // A new drive bumps the start counter and odometer, which alone is not a change worth saving.
        val nextDrive = tables + (0x34 to "613418F60217E10117B70216DF0116DA00C86000".hexBytes())
        assertEquals(false, com.suzukiscope.core.ecu.EcuTables.differs(tables, nextDrive))
        val newFault = tables + (0x41 to "61410113000000438087FFFF55".hexBytes())
        assertEquals(true, com.suzukiscope.core.ecu.EcuTables.differs(tables, newFault))
    }

    @Test
    fun readsSnapshotInTheBackgroundOneTableEveryFewCycles() = runTest {
        val requests = mutableListOf<String>()
        val transport = LoopbackTransport { bytes ->
            val cmd = bytes.decodeToString().trim()
            requests += cmd
            (if (cmd.startsWith("21")) "61" + cmd.substring(2) + "00" else "OK").plus("\r>").encodeToByteArray()
        }
        val source = Elm327LiveDataSource(transport, Elm327Protocol.CAN_11BIT_500K)
        var snapshot: EcuSnapshot? = null
        source.onBackgroundSnapshot = { snapshot = it }
        source.connect()
        requests.clear()
        val tableCount = com.suzukiscope.core.ecu.EcuTables.SNAPSHOT_TABLES.size
        repeat(Elm327LiveDataSource.BACKGROUND_SNAPSHOT_EVERY_CYCLES * tableCount - 1) { source.endCycle() }
        assertEquals(null, snapshot)
        assertEquals(tableCount - 1, requests.count { it.startsWith("21") })
        source.endCycle()
        assertEquals(tableCount, snapshot?.tables?.size)
        repeat(Elm327LiveDataSource.BACKGROUND_SNAPSHOT_EVERY_CYCLES * 2) { source.endCycle() }
        assertEquals(tableCount, requests.count { it.startsWith("21") })
    }

    private fun String.hexBytes() = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun backgroundReadsWaitForLightLoadAndTableRetriesDoNotBackOff() = runTest {
        val requests = mutableListOf<String>()
        val source = Elm327LiveDataSource(canAdapter(requests), Elm327Protocol.CAN_11BIT_500K)
        source.connect()
        var quiet = false
        source.backgroundReadAllowed = { quiet }
        requests.clear()
        repeat(Elm327LiveDataSource.BACKGROUND_SNAPSHOT_EVERY_CYCLES * 3) { source.endCycle() }
        assertEquals(0, requests.count { it.startsWith("21") })
        quiet = true
        source.endCycle()
        assertEquals(1, requests.count { it.startsWith("21") })

        val table = FieldDefinition(
            id = "t",
            label = "t",
            unit = "",
            request = RequestSpec(targetAddress = 0x7E0, mode = 0x21, params = listOf(0x00), responsePrefixBytes = 0, responseSuffixBytes = 0),
            decode = DecodeSpec(skipBytes = 2, byteLength = 1),
        )
        assertEquals(Elm327LiveDataSource.CAN_TABLE_RETRY_MS, source.retryDelayMs(table, 4))
    }

    private fun obdField(id: String, pids: List<Int>, decode: DecodeSpec, fallback: FieldFallback? = null) = FieldDefinition(
        id = id,
        label = id,
        unit = "",
        request = RequestSpec(targetAddress = 0x7E0, mode = OBD_MODE_CURRENT_DATA, params = pids, responsePrefixBytes = 0, responseSuffixBytes = 0),
        decode = decode,
        fallback = fallback,
    )

    @Test
    fun batchesObdPidsOfACycleIntoOneRequest() = runTest {
        val requests = mutableListOf<String>()
        val source = Elm327LiveDataSource(canAdapter(requests), Elm327Protocol.CAN_11BIT_500K)
        source.connect()
        val rpm = obdField("rpm", listOf(0x0C), DecodeSpec(skipBytes = 2, byteLength = 2, scale = 0.25))
        val relativeBoost = obdField("boost", listOf(0x0B, 0x33), DecodeSpec(skipBytes = 2, byteLength = 1, scale = 0.01, subtractSkipBytes = 4))

        source.beginCycle(listOf(rpm, relativeBoost))
        assertEquals(1726.0, source.poll(rpm))
        assertEquals(0.16, source.poll(relativeBoost), 1e-9)
        assertEquals(1, requests.count { it.startsWith("01") && it !in setOf("0100", "0120") })
    }

    @Test
    fun usesFallbackWhenPidUnsupported() = runTest {
        val source = Elm327LiveDataSource(canAdapter(mutableListOf()), Elm327Protocol.CAN_11BIT_500K)
        source.connect()
        val fallback = FieldFallback(
            RequestSpec(targetAddress = 0x7E0, mode = 0x21, params = listOf(0x00), responsePrefixBytes = 0, responseSuffixBytes = 0),
            DecodeSpec(skipBytes = 2, byteLength = 1),
        )
        // PID 0E is not in the supported set above, so the Suzuki request is used instead.
        val timing = obdField("timing", listOf(0x0E), DecodeSpec(skipBytes = 2, byteLength = 1), fallback)

        source.beginCycle(listOf(timing))
        assertEquals(42.0, source.poll(timing))
    }
}
