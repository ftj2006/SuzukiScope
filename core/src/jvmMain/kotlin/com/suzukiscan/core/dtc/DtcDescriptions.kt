package com.suzukiscan.core.dtc

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Suzuki's own DTC descriptions extracted from decompiled sz-viewer sources (see
 * reference/extract_fields.py). ~1449 codes with an English description. Codes not
 * present here (e.g. generic OBD-II codes handled elsewhere in the original app) fall
 * back to the bare formatted code with no description.
 */
object DtcDescriptions {
    private val json = Json { ignoreUnknownKeys = true }

    val all: Map<String, String> by lazy {
        val text = requireNotNull(
            DtcDescriptions::class.java.getResourceAsStream("/suzuki-dtc-codes.json"),
        ) { "suzuki-dtc-codes.json resource not found on classpath" }.bufferedReader().readText()
        val raw: Map<String, DtcDescriptionEntry> = json.decodeFromString(text)
        raw.mapValues { it.value.description }
    }

    fun describe(code: String): String? = all[code]
}

@Serializable
private data class DtcDescriptionEntry(val description: String, val comment: String)
