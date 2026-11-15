package com.suzukiscope.core.dtc

import com.suzukiscope.core.elm327.Elm327Client
import com.suzukiscope.core.transport.LoopbackTransport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DtcSessionTest {

    private suspend fun session(reply: (cmd: String, header: String) -> String): DtcSession {
        var header = ""
        val client = Elm327Client(LoopbackTransport { bytes ->
            val cmd = bytes.decodeToString().trim()
            if (cmd.startsWith("ATSH")) header = cmd.removePrefix("ATSH")
            (if (cmd.startsWith("AT")) "OK" else reply(cmd, header)).plus("\r>").encodeToByteArray()
        })
        client.connect()
        return DtcSession(client)
    }

    @Test
    fun readsSuzukiCodesWithStatus() = runTest {
        val s = session { cmd, _ -> if (cmd == "18000000") "58020120E0412020" else if (cmd.startsWith("1800")) "5800" else "NO DATA" }
        val codes = s.readDtcs(0x7E0, isCan = true)
        assertEquals(listOf("P0120", "C0120"), codes.map { it.code })
        assertEquals(listOf(0xE0, 0x20), codes.map { it.statusByte })
        assertEquals("present now, warning light on (status E0)", describeDtcStatus(0xE0))
    }

    @Test
    fun reportsEachEngineReadMethodSeparately() = runTest {
        val s = session { cmd, _ ->
            when {
                cmd.startsWith("18") -> "7F1811"
                cmd.startsWith("19") -> "7F1911"
                cmd == "03" -> "43010120"
                cmd == "07" -> "4700"
                cmd == "0A" -> "7F0A11"
                else -> "NO DATA"
            }
        }
        val results = s.readEngine().associateBy { it.source }
        assertTrue(results.getValue(DtcSource.SUZUKI).error!!.contains("NRC 11"))
        assertEquals(listOf("P0120"), results.getValue(DtcSource.STORED).codes.map { it.code })
        assertNull(results.getValue(DtcSource.PENDING).error)
        assertTrue(results.getValue(DtcSource.PENDING).codes.isEmpty())
        assertTrue(results.getValue(DtcSource.PERMANENT).error != null)
    }

    @Test
    fun fallsBackToBroadcastWhenTheEngineEcuIgnoresObdModes() = runTest {
        val s = session { cmd, header ->
            when {
                header != "7DF" -> "NO DATA"
                cmd == "03" -> "43010120"
                cmd == "07" -> "4700"
                cmd == "0A" -> "4A00"
                else -> "NO DATA"
            }
        }
        val results = s.readEngine().associateBy { it.source }
        assertEquals(listOf("P0120"), results.getValue(DtcSource.STORED).codes.map { it.code })
        assertNull(results.getValue(DtcSource.PERMANENT).error)
    }

    @Test
    fun clearFallsBackToObdMode04() = runTest {
        val s = session { cmd, _ ->
            when (cmd) {
                "14FF00" -> "7F1422"
                "04" -> "44"
                else -> "NO DATA"
            }
        }
        assertEquals("Codes cleared (OBD-II mode 04)", s.clearEngine())
    }

    @Test
    fun clearAcceptsResponsePendingFollowedByTheAnswer() = runTest {
        val s = session { cmd, _ -> if (cmd == "14FF00") "7F1478\r54FF00" else "NO DATA" }
        assertEquals("Codes cleared (Suzuki service 14)", s.clearEngine())
    }

    @Test
    fun readsEveryGroupLikeSzViewerAndMergesCodes() = runTest {
        val requests = mutableListOf<String>()
        val s = session { cmd, _ ->
            requests += cmd
            when (cmd) {
                "18000000" -> "5801012060"
                "1800FFFF" -> "5802012060412020"
                else -> if (cmd.startsWith("1800")) "7F1812" else "NO DATA"
            }
        }
        assertEquals(listOf("P0120", "C0120"), s.readDtcs(0x7E0, isCan = true).map { it.code })
        assertEquals(listOf("18000000", "18004000", "18008000", "1800C000", "1800FF00", "1800FFFF"), requests.filter { it.startsWith("18") })
    }

    @Test
    fun fallsBackToUdsWhenServiceEighteenIsUnsupported() = runTest {
        val s = session { cmd, _ ->
            when (cmd) {
                "1902FF" -> "5902FF01200A2F"
                else -> if (cmd.startsWith("18")) "7F1811" else "NO DATA"
            }
        }
        assertEquals(listOf("P0120-0A"), s.readDtcs(0x7E0, isCan = true).map { it.code })
    }
}
