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
        val s = session { cmd, _ -> if (cmd == "1802FF00") "58020120E0412020" else "NO DATA" }
        val codes = s.readDtcs(0x7E0, isCan = true)
        assertEquals(listOf("P0120", "C0120"), codes.map { it.code })
        assertEquals(listOf(0xE0, 0x20), codes.map { it.statusByte })
        assertEquals("present now, warning light on (status E0)", describeDtcStatus(0xE0))
    }

    @Test
    fun reportsEachEngineReadMethodSeparately() = runTest {
        val s = session { cmd, _ ->
            when (cmd) {
                "1802FF00" -> "7F1811"
                "03" -> "43010120"
                "07" -> "4700"
                "0A" -> "7F0A11"
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
}
