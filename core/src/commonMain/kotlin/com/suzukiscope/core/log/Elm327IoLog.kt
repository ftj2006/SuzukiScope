package com.suzukiscope.core.log

import com.suzukiscope.core.session.currentTimeMillis

enum class IoDirection { SENT, RECEIVED, ERROR }

/** Some exceptions carry no message (e.g. Kotlin's `!!`), so fall back to the exception type
 * rather than showing a bare "null" in status text/logs. */
fun describeError(e: Throwable): String = e.message ?: e::class.simpleName ?: "Unknown error"

data class IoLogEntry(val timestampMs: Long, val direction: IoDirection, val text: String)

/**
 * Rolling raw AT-command/response trace, kept independent of the decoded [LiveDataRecorder].
 * Intended for diagnosing real-hardware quirks (timeouts, malformed frames, adapter-specific
 * responses) on the first few real vehicle connections — safe to remove/ignore once the
 * protocol handling has been proven against real hardware.
 */
// Sized to hold several minutes of polling plus the periodic PERF summaries.
class Elm327IoLog(private val maxEntries: Int = 20_000) {
    private val entries = ArrayDeque<IoLogEntry>()

    @kotlin.jvm.Synchronized
    fun append(direction: IoDirection, text: String) {
        entries.addLast(IoLogEntry(currentTimeMillis(), direction, text))
        if (entries.size > maxEntries) entries.removeFirst()
    }

    @kotlin.jvm.Synchronized
    fun snapshot(): List<IoLogEntry> = entries.toList()

    @kotlin.jvm.Synchronized
    fun clear() = entries.clear()

    fun toText(): String {
        val entries = snapshot()
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
