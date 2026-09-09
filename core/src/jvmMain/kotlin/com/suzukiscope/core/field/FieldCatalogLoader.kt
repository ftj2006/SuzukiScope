package com.suzukiscope.core.field

import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

/**
 * Loads the full field catalog extracted from the decompiled sz-viewer sources
 * (588 candidate fields across every module/protocol variant found — see
 * reference/extract_fields.py for how it was generated and its known limitations:
 * labels/units/byte-offsets are read directly from decompiled source, but request
 * addressing for some CAN/Jeep-platform variants may be unresolved (see each
 * entry's provenance in the JSON, ignored here since FieldDefinition doesn't need it).
 * All entries ship disabled — the user chooses which to show via the config screen.
 */
object FieldCatalogLoader {
    private val json = Json { ignoreUnknownKeys = true }

    fun loadFullCatalog(): List<FieldDefinition> {
        val text = requireNotNull(
            FieldCatalogLoader::class.java.getResourceAsStream("/suzuki-fields-catalog.json"),
        ) { "suzuki-fields-catalog.json resource not found on classpath" }.bufferedReader().readText()
        val catalog: List<FieldDefinition> = json.decodeFromString(text)
        val preferredLabels = listOf(
            "Intake Temp",
            "Air Flow",
            "Intake manifold absolute pressure",
            "Ignition-1",
            "Injector power",
            "O2 Sensor (Bank 1, Sensor 1)",
            "Lambda Bank1 Front",
            "Battery voltage",
            "Vehicle speed (ECM)",
            "Oil pressure",
            "Fuel pressure",
            "Primary pulley speed",
            "Secondary pulley speed",
            "Turbine speed",
            "Output shaft speed",
            "Gear actual ratio",
            "Line pressure",
        )
        val preferred = preferredLabels.mapNotNull { label -> catalog.firstOrNull { it.label == label } }
        val preferredIds = preferred.mapTo(HashSet()) { it.id }
        return preferred + catalog.filterNot { it.id in preferredIds }
    }
}
