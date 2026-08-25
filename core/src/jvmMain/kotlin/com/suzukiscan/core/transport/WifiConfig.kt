package com.suzukiscan.core.transport

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** User-editable ELM327 Wi-Fi adapter address, persisted so it survives app restarts. */
@Serializable
data class WifiConfig(
    val host: String = WifiTransport.DEFAULT_HOST,
    val port: Int = WifiTransport.DEFAULT_PORT,
) {
    companion object {
        fun load(file: File): WifiConfig =
            if (file.exists()) {
                runCatching { Json.decodeFromString(serializer(), file.readText()) }.getOrDefault(WifiConfig())
            } else {
                WifiConfig()
            }
    }

    fun save(file: File) {
        file.parentFile?.mkdirs()
        file.writeText(Json.encodeToString(serializer(), this))
    }
}
