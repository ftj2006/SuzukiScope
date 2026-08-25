package com.suzukiscan.core.field

import java.io.File

/** Persists a [FieldRegistry] (gauge order, enabled set, min/max/thresholds) to a JSON file. */
object FieldRegistryStore {
    fun load(registry: FieldRegistry, file: File) {
        if (file.exists()) registry.loadFromJson(file.readText())
    }

    fun save(registry: FieldRegistry, file: File) {
        file.parentFile?.mkdirs()
        file.writeText(registry.toJson())
    }
}
