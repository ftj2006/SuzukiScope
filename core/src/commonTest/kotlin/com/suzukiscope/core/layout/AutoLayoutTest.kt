package com.suzukiscope.core.layout

import kotlin.test.Test
import kotlin.test.assertEquals

class AutoLayoutTest {
    private val config = AutoLayoutConfig()

    private fun AutoLayoutConfig.arrange(rows: List<Int?>) = arrange(rows.map { Placement(it, null) })

    private fun AutoLayoutConfig.shape(assigned: List<Int?>) = arrange(assigned).map { row ->
        row.size.name.first() + ":" + row.slots.joinToString("") { if (it == null) "_" else "o" }
    }

    @Test
    fun usesTheChosenSlotsInConfiguredRows() {
        val arranged = config.arrange(listOf(Placement(0, 3), Placement(0, 0), Placement(1, 4)))
        assertEquals(listOf(listOf(1, null, null, 0), listOf(null, null, null, null, 2)), arranged.map { it.slots })
    }

    @Test
    fun movesClashingOrOutOfRangeSlotsToFreeOnes() {
        val arranged = config.arrange(listOf(Placement(0, 1), Placement(0, 1), Placement(0, 9)))
        assertEquals(listOf(2, 0, 1, null), arranged.single().slots)
    }

    @Test
    fun sendsOverflowToTheRemainingRows() {
        val narrow = AutoLayoutConfig(rows = listOf(AutoRow(TileSize.EXTRA_LARGE, 1)))
        assertEquals(listOf(listOf(0), listOf(null, 1, null, null)), narrow.arrange(listOf(Placement(0, 0), Placement(0, 0))).map { it.slots })
    }

    @Test
    fun fillsFromTheMiddleLeavingBottomRightForLast() {
        assertEquals(listOf("E:_o__"), config.shape(listOf(null)))
        assertEquals(listOf("E:_oo_"), config.shape(listOf(null, null)))
        assertEquals(listOf("E:ooo_"), config.shape(listOf(null, null, null)))
        assertEquals(listOf("L:_ooo_"), config.shape(listOf(1, 1, 1)))
        assertEquals(listOf("L:oooo_"), config.shape(listOf(1, 1, 1, 1)))
    }

    @Test
    fun placesFieldsInTheirAssignedRowsAndSkipsEmptyOnes() {
        assertEquals(listOf("E:_oo_", "L:_ooo_", "E:_o__"), config.shape(listOf(0, 0, 1, 1, 1, null)))
        assertEquals(listOf("L:_oo__"), config.shape(listOf(1, 1)))
        assertEquals(listOf("E:oooo", "E:ooo_"), config.shape(List(7) { null }))
    }

    @Test
    fun keepsDashboardOrderWithinARow() {
        assertEquals(listOf(listOf(null, 0, 2, null), listOf(null, 1, null, null)), config.arrange(listOf(0, null, 0)).map { it.slots })
    }

    @Test
    fun treatsRowsBeyondTheConfiguredCountAsRemaining() {
        assertEquals(listOf("E:_o__"), AutoLayoutConfig(rows = emptyList()).shape(listOf(0)))
    }

    @Test
    fun roundTripsThroughJson() {
        assertEquals(config, AutoLayoutConfig.fromJson(config.toJson()))
    }
}
