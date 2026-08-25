package com.suzukiscan.core.field

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
}
