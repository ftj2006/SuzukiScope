package com.suzukiscope.ui

import com.suzukiscope.core.field.FieldDefinition

/** Friendly names for the module id prefixes seen in the field catalog (see reference/extract_fields.py). */
private val friendlyModuleNames = mapOf(
    "abs" to "ABS / Brakes",
    "adm" to "ADM (Auto Distance)",
    "alc" to "ALC (Auto Light Control)",
    "at" to "Automatic Transmission",
    "bcm" to "Body Control Module",
    "bmu" to "Battery Management",
    "dcbs" to "Combined Brake System",
    "ecall" to "eCall",
    "engine" to "Engine",
    "hvac" to "Climate Control (HVAC)",
    "ipc" to "Instrument Cluster",
    "isg" to "Idle Stop/Start (ISG)",
    "mfl" to "Steering Wheel Controls",
    "motoengine" to "Motorcycle Engine",
    "powertrain" to "Powertrain",
    "ps" to "Power Steering",
    "renaultdiesel" to "Diesel Engine",
    "tpms" to "Tyre Pressure (TPMS)",
)

/** A module selectable on the DTC screen \u2014 [isCan] picks CAN-UDS (0/0 byte framing) vs
 * KWP2000 K-Line (3/1 byte framing) when reading/clearing codes. */
data class ModuleOption(
    val label: String,
    val targetAddress: Int,
    val isFunctionalAddress: Boolean,
    val isCan: Boolean,
)

/** Distinct modules seen across the loaded fields, for the DTC module picker. */
fun moduleOptionsFrom(fields: List<FieldDefinition>): List<ModuleOption> =
    fields
        .map { it.id.substringBefore('.') }
        .distinct()
        .map { prefix ->
            val field = fields.first { it.id.substringBefore('.') == prefix }
            ModuleOption(
                label = friendlyModuleNames[prefix] ?: prefix.replaceFirstChar { c -> c.uppercase() },
                targetAddress = field.request.targetAddress,
                isFunctionalAddress = field.request.isFunctionalAddress,
                isCan = field.request.responsePrefixBytes == 0 && field.request.responseSuffixBytes == 0,
            )
        }
        .sortedBy { it.label }
