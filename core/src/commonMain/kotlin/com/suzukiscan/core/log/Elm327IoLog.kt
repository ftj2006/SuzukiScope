package com.suzukiscan.core.log

import com.suzukiscan.core.session.currentTimeMillis

enum class IoDirection { SENT, RECEIVED, ERROR }

data class IoLogEntry(val timestampMs: Long, val direction: IoDirection, val text: String)

/**
 * Rolling raw AT-command/response trace, kept independent of the decoded [LiveDataRecorder].
 * Intended for diagnosing real-hardware quirks (timeouts, malformed frames, adapter-specific
 * responses) on the first few real vehicle connections — safe to remove/ignore once the
 * protocol handling has been proven against real hardware.
 */
class Elm327IoLog(private val maxEntries: Int = 4000) {
    private val entries = ArrayList<IoLogEntry>()

    fun append(direction: IoDirection, text: String) {
        entries.add(IoLogEntry(currentTimeMillis(), direction, text))
        if (entries.size > maxEntries) entries.removeAt(0)
    }

    fun snapshot(): List<IoLogEntry> = entries.toList()

    fun clear() = entries.clear()

    fun toText(): String {
        if (entries.isEmpty()) return "No adapter traffic logged yet."
        return entries.joinToString("\n") { e ->
            val tag = when (e.direction) {
                IoDirection.SENT -> "TX"
                IoDirection.RECEIVED -> "RX"
                IoDirection.ERROR -> "ERR"
            }
            "${e.timestampMs} [$tag] ${e.text}"
        }
    }
}
