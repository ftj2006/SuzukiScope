package com.suzukiscope.core.field

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FieldCatalogLoaderTest {

    @Test
    fun loadsFullCatalogWithManyDisabledFields() {
        val catalog = FieldCatalogLoader.loadFullCatalog()
        assertTrue(catalog.size > 100, "expected the full extraction catalog to contain many fields, got ${catalog.size}")
        assertTrue(catalog.all { !it.enabled }, "catalog entries should ship disabled by default")
        assertTrue(catalog.map { it.id }.toSet().size == catalog.size, "field ids should be unique")
    }

    @Test
    fun registryMergesCatalogWithoutOverridingExistingEnabledState() {
        val registry = FieldRegistry.withExampleDefaults()
        val before = registry.fields.size
        registry.mergeCatalog(FieldCatalogLoader.loadFullCatalog())
        assertTrue(registry.fields.size > before)
        // Example defaults are still enabled after merging in the (disabled) full catalog.
        assertTrue(registry.fields.filter { it.id.startsWith("engine.coolant_temp") }.all { it.enabled })
    }

    @Test
    fun unconfirmedCandidatesAreExplicitlyLabelledAndSearchable() {
        val catalog = FieldCatalogLoader.loadFullCatalog().associateBy { it.id }
        val fuelStatus = catalog.getValue("engine.can.00.inferred_fuel_status_p15")
        val wheelSpeed = catalog.getValue("abs.can.261_56100_61mb_00.inferred_wheel_speed_a_p7")

        assertTrue(fuelStatus.label.startsWith("Unconfirmed:"))
        assertTrue("open loop" in fuelStatus.searchTerms)
        assertTrue(wheelSpeed.label.startsWith("Unconfirmed:"))
        assertTrue("wheel slip" in wheelSpeed.searchTerms)
        assertTrue(!fuelStatus.enabled && !wheelSpeed.enabled)

        assertEquals(2, catalog.getValue("engine.can.00.o2_sensor_bank_1_sensor_1_p74").decimals)
        assertEquals(2, catalog.getValue("engine.can.00.o2_sensor_bank_1_sensor_2_p75").decimals)
        assertEquals(2, catalog.getValue("engine.can.00.a_f_sensor_bank1_sensor_1_p82").decimals)
    }
}
