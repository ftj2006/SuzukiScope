package com.suzukiscope.core.ecu

/**
 * Decodings of the engine ECU's `21 xx` tables worked out against the car
 * (see reference/ECU_SNAPSHOT_SUMMARY.md). Offsets include the `61 xx` reply header.
 */
object EcuTables {
    /** Tables the engine ECU answered in the 21 00-21 FF probe. */
    val SNAPSHOT_TABLES = listOf(0x00, 0x27, 0x28, 0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x3A, 0x3C, 0x3D) +
        (0x40..0x49).toList() + listOf(0xA2, 0xD0)

    data class FaultEntry(val source: String, val code: String, val odometerKm: Int?)

    data class VehicleSummary(
        val engineStarts: Int?,
        val odometerKm: Int?,
        /** Learned (long-term) fuel trims in %, from the 21 35 pairs. */
        val learnedTrims: List<Double>,
        val latestFault: FaultEntry?,
    )

    private fun ByteArray.u8(at: Int) = this[at].toInt() and 0xFF
    private fun ByteArray.u16(at: Int) = (u8(at) shl 8) or u8(at + 1)
    private fun ByteArray.u24(at: Int) = (u8(at) shl 16) or u16(at + 1)
    private fun ByteArray.trim(at: Int) = (u8(at) - 128) * 0.78125

    private fun ByteArray.code(at: Int): String? {
        if (u8(at) == 0 && u8(at + 1) == 0) return null
        val hi = u8(at)
        return "%c%d%X%02X".format("PCBU"[hi shr 6], (hi shr 4) and 3, hi and 0x0F, u8(at + 1))
    }

    private fun trims(values: List<Double>) = values.joinToString(" / ") { "%+.1f%%".format(it) }

    /** Human-readable decoding appended to a table's line in the snapshot file, or "" when nothing is known. */
    fun annotate(id: Int, bytes: ByteArray): String = when {
        id == 0xD0 -> "  (text: " + bytes.drop(2).map { b -> val c = b.toInt() and 0xFF; if (c in 0x20..0x7E) c.toChar() else '.' }.joinToString("") + ")"
        id == 0x34 && bytes.size >= 19 -> "  (engine starts: ${bytes.u16(2)}, odometer: ${bytes.u24(16)} km)"
        id == 0x35 && bytes.size >= 23 ->
            "  (record: ${bytes.code(3) ?: "no code"}, odometer stamp ${bytes.u24(15)} km, baro ${bytes.u8(18)} kPa, " +
                "trims ${trims((19..22).map { bytes.trim(it) })})"
        (id == 0x30 || id == 0x31) && bytes.size >= 25 ->
            "  (record ${bytes.u8(2)}: ${bytes.code(23) ?: "no code"}, odometer stamp ${bytes.u24(20)} km, " +
                "trims ${trims((11..14).map { bytes.trim(it) })})"
        id == 0x3A && bytes.size >= 4 -> {
            val entries = (2 until bytes.size step 2).map { bytes.u8(it) }
            "  (start log, newest first: ${entries.take(10).joinToString(" ") { (it and 0x7F).toString() }} ...; " +
                "${entries.count { it and 0x80 != 0 }} flagged)"
        }
        id in 0x40..0x49 && bytes.size >= 4 -> bytes.code(2)?.let { "  (stored fault code: $it)" } ?: ""
        else -> ""
    }

    /**
     * The table as compared between snapshots, or null to ignore it: live data, the per-start log and
     * the start counter/odometer change on every drive and would make every snapshot look new.
     */
    fun comparable(id: Int, bytes: ByteArray): String? {
        val masked = bytes.copyOf()
        when (id) {
            0x00, 0x3A -> return null
            0x34 -> listOf(2, 3, 16, 17, 18).filter { it < masked.size }.forEach { masked[it] = 0 }
            0x35 -> listOf(15, 16, 17).filter { it < masked.size }.forEach { masked[it] = 0 }
        }
        return masked.joinToString("") { "%02X".format(it) }
    }

    /** True when any table read in both snapshots differs (ignoring the values [comparable] masks). */
    fun differs(previous: Map<Int, ByteArray>, current: Map<Int, ByteArray>): Boolean =
        previous.isEmpty() || current.any { (id, bytes) ->
            val before = previous[id] ?: return@any false
            comparable(id, bytes) != comparable(id, before)
        }

    fun summarise(tables: Map<Int, ByteArray>): VehicleSummary {
        val t34 = tables[0x34]?.takeIf { it.size >= 19 }
        val t35 = tables[0x35]?.takeIf { it.size >= 23 }
        return VehicleSummary(
            engineStarts = t34?.u16(2),
            odometerKm = t34?.u24(16),
            learnedTrims = t35?.let { listOf(it.trim(20), it.trim(22)) }.orEmpty(),
            latestFault = t35?.code(3)?.let { FaultEntry("21 35", it, t35.u24(15)) },
        )
    }

    /** Every fault the engine ECU keeps, odometer-stamped records first; the ten freeze-frame slots carry no odometer. */
    fun faultHistory(tables: Map<Int, ByteArray>): List<FaultEntry> {
        val stamped = listOfNotNull(
            tables[0x35]?.takeIf { it.size >= 23 }?.let { b -> b.code(3)?.let { FaultEntry("21 35", it, b.u24(15)) } },
            tables[0x31]?.takeIf { it.size >= 25 }?.let { b -> b.code(23)?.let { FaultEntry("21 31", it, b.u24(20)) } },
            tables[0x30]?.takeIf { it.size >= 25 }?.let { b -> b.code(23)?.let { FaultEntry("21 30", it, b.u24(20)) } },
        )
        val slots = (0x40..0x49).mapNotNull { id ->
            tables[id]?.takeIf { it.size >= 4 }?.code(2)?.let { FaultEntry("21 %02X".format(id), it, null) }
        }
        return stamped + slots
    }

    fun toText(tables: Map<Int, ByteArray>): String =
        tables.entries.joinToString("\n") { (id, bytes) -> "%02X=%s".format(id, bytes.joinToString("") { "%02X".format(it) }) }

    fun fromText(text: String): Map<Int, ByteArray> = text.lines().mapNotNull { line ->
        val (id, hex) = line.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
        runCatching { id.toInt(16) to ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }.getOrNull()
    }.toMap()
}
