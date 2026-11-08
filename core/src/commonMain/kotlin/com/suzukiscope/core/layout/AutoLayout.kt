package com.suzukiscope.core.layout

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs

@Serializable
enum class TileSize { EXTRA_LARGE, LARGE }

/** An Android Auto grid row: its tile size and how many tiles of that size fit across it. */
@Serializable
data class AutoRow(val size: TileSize, val width: Int)

/**
 * Android Auto dashboard rows: fields assigned to a configured row (see FieldDefinition.autoRow)
 * go there; all others flow into as many [remaining] rows as they need.
 */
@Serializable
data class AutoLayoutConfig(
    val rows: List<AutoRow> = listOf(AutoRow(TileSize.EXTRA_LARGE, 4), AutoRow(TileSize.LARGE, 5)),
    val remaining: AutoRow = AutoRow(TileSize.EXTRA_LARGE, 4),
) {
    /** The configured row a field's assignment points to, or null for the remaining rows. */
    fun rowOf(autoRow: Int?): Int? = autoRow?.takeIf { it in rows.indices }

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun fromJson(text: String): AutoLayoutConfig = json.decodeFromString(serializer(), text)
    }
}

/** A filled row: each slot holds a gauge index, or null for an invisible spacer tile. */
data class ArrangedRow(val size: TileSize, val slots: List<Int?>)

/** Where a gauge goes: a configured row and slot within it, or null row for the remaining rows. */
data class Placement(val row: Int?, val slot: Int?)

/**
 * Slots of configured [row] for its [members] (gauge index to requested slot). Gauges without a
 * valid free slot take the free ones from the middle outwards; any that don't fit are returned
 * as overflow, for the remaining rows.
 */
fun AutoLayoutConfig.slotsForRow(row: Int, members: List<Pair<Int, Int?>>): Pair<List<Int?>, List<Int>> {
    val width = rows[row].width.coerceAtLeast(1)
    val slots = arrayOfNulls<Int>(width)
    val unplaced = ArrayList<Int>()
    for ((gauge, slot) in members) {
        if (slot != null && slot in 0 until width && slots[slot] == null) slots[slot] = gauge else unplaced.add(gauge)
    }
    val free = middleOutOrder(width).filter { slots[it] == null }
    unplaced.take(free.size).forEachIndexed { i, gauge -> slots[free[i]] = gauge }
    return slots.toList() to unplaced.drop(free.size)
}

/**
 * Lays out gauges with the given [placements] (in dashboard order). Configured rows use each
 * gauge's chosen slot; the remaining rows fill from the middle outwards, left before right, so
 * the bottom-right tile (under the on-screen buttons) fills last. Every row is padded to its full
 * width because the head unit drops rows after an incomplete one; empty configured rows are skipped.
 */
fun AutoLayoutConfig.arrange(placements: List<Placement>): List<ArrangedRow> {
    val result = ArrayList<ArrangedRow>()
    val remainingGauges = placements.indices.filter { rowOf(placements[it].row) == null }.toMutableList()
    rows.forEachIndexed { index, row ->
        val members = placements.indices.filter { rowOf(placements[it].row) == index }.map { it to placements[it].slot }
        if (members.isEmpty()) return@forEachIndexed
        val (slots, overflow) = slotsForRow(index, members)
        result.add(ArrangedRow(row.size, slots))
        remainingGauges.addAll(overflow)
    }
    val width = remaining.width.coerceAtLeast(1)
    for (chunk in remainingGauges.sorted().chunked(width)) {
        val used = middleOutOrder(width).take(chunk.size).sorted()
        result.add(ArrangedRow(remaining.size, (0 until width).map { slot -> used.indexOf(slot).takeIf { it >= 0 }?.let { chunk[it] } }))
    }
    return result
}

/** Slot indices from the middle outwards, left before right (4 -> 1, 2, 0, 3). */
fun middleOutOrder(columns: Int): List<Int> =
    (0 until columns).sortedWith(compareBy({ abs(2 * it - (columns - 1)) }, { it }))
