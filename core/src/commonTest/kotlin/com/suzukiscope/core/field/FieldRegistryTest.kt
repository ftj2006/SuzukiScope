package com.suzukiscope.core.field

import kotlin.test.Test
import kotlin.test.assertEquals

class FieldRegistryTest {

    private val table00 = RequestSpec(targetAddress = 0x7E0, mode = 0x21, params = listOf(0x00), responsePrefixBytes = 0, responseSuffixBytes = 0)

    private fun field(id: String, skip: Int, enabled: Boolean = false, bit: Int? = null) =
        FieldDefinition(id, id, "", table00, DecodeSpec(skipBytes = skip, bit = bit), enabled = enabled)

    @Test
    fun mergeCatalogLeavesOutFieldsReadingTheSameBytes() {
        val registry = FieldRegistry(listOf(field("engine.temp", 44, enabled = true)))
        registry.mergeCatalog(
            listOf(
                field("engine.can.00.engine_temp_p42", 44),
                field("engine.can.00.fan_p126b0", 128, bit = 0),
                field("at.can.00.fan_copy_p126b0", 128, bit = 0),
                field("engine.can.00.pump_p126b1", 128, bit = 1),
            ),
        )
        assertEquals(listOf("engine.temp", "engine.can.00.fan_p126b0", "engine.can.00.pump_p126b1"), registry.fields.map { it.id })
    }

    @Test
    fun mergeCatalogDropsUnselectedFieldsNoLongerInTheCatalogAndRefreshesDecode() {
        val registry = FieldRegistry()
        registry.mergeCatalog(listOf(field("engine.can.00.old_p1", 3), field("engine.can.00.kept_p2", 4), field("engine.can.00.mine_p5", 7)))
        registry.setEnabled("engine.can.00.mine_p5", true)
        registry.mergeCatalog(listOf(field("engine.can.00.kept_p2", 4, bit = 3)))
        assertEquals(listOf("engine.can.00.kept_p2", "engine.can.00.mine_p5"), registry.fields.map { it.id })
        assertEquals(3, registry.get("engine.can.00.kept_p2")?.decode?.bit)
    }

    @Test
    fun mergeCatalogRefreshesSearchAliasesWithoutChangingSelection() {
        val id = "engine.can.00.test_p17"
        val registry = FieldRegistry(listOf(field(id, 19, enabled = true).copy(searchTerms = listOf("old alias"))))
        registry.mergeCatalog(listOf(field(id, 19).copy(searchTerms = listOf("new alias"))))

        assertEquals(listOf("new alias"), registry.get(id)?.searchTerms)
        assertEquals(true, registry.get(id)?.enabled)
    }
}
