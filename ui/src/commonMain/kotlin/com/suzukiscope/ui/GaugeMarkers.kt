package com.suzukiscope.ui

import com.suzukiscope.core.session.currentTimeMillis
import kotlinx.coroutines.flow.MutableStateFlow

enum class LimitLevel { WARNING, CRITICAL }

/**
 * Short-term peak and recent limit hits per field, shared by the phone and Android Auto gauges.
 * Fed every reading; queried with the render time, so falling and expiry need no timer.
 */
class GaugeMarkers(private val now: () -> Long = ::currentTimeMillis) {
    private data class Track(
        val held: Double,
        val heldAtMs: Long,
        val latest: Double,
        val range: Double,
        val warningAtMs: Long? = null,
        val criticalAtMs: Long? = null,
    )

    // Written by the polling coroutine, read by the UI threads: swapped whole, never mutated.
    private val tracks = MutableStateFlow<Map<String, Track>>(emptyMap())

    fun record(id: String, value: Double, range: Double, warning: Double?, critical: Double?) {
        val at = now()
        val old = tracks.value[id] ?: Track(value, at, value, range)
        val rising = value >= shortPeak(old, at)
        val track = old.copy(
            held = if (rising) value else old.held,
            heldAtMs = if (rising) at else old.heldAtMs,
            latest = value,
            range = range,
            warningAtMs = if (warning != null && value >= warning) at else old.warningAtMs,
            criticalAtMs = if (critical != null && value >= critical) at else old.criticalAtMs,
        )
        tracks.value = tracks.value + (id to track)
    }

    /** Holds for [HOLD_MS], then falls towards the live value; null when it sits on the live value. */
    fun shortPeak(id: String, at: Long = now()): Double? {
        val track = tracks.value[id] ?: return null
        return shortPeak(track, at).takeIf { it > track.latest + track.range * 0.01 }
    }

    /** The worst limit reached within the last [LIMIT_SHOWN_MS], for the tinted background track. */
    fun limitHit(id: String, at: Long = now()): LimitLevel? {
        val track = tracks.value[id] ?: return null
        return when {
            track.criticalAtMs?.let { at - it < LIMIT_SHOWN_MS } == true -> LimitLevel.CRITICAL
            track.warningAtMs?.let { at - it < LIMIT_SHOWN_MS } == true -> LimitLevel.WARNING
            else -> null
        }
    }

    /** True while any marker is still moving or about to expire, so the car screen keeps redrawing. */
    fun animating(at: Long = now()): Boolean = tracks.value.keys.any { shortPeak(it, at) != null || limitHit(it, at) != null }

    fun clear() {
        tracks.value = emptyMap()
    }

    private fun shortPeak(track: Track, at: Long): Double {
        val falling = (at - track.heldAtMs - HOLD_MS).coerceAtLeast(0)
        return maxOf(track.latest, track.held - track.range * FALL_PER_S * falling / 1000.0)
    }

    companion object {
        const val HOLD_MS = 2000L
        const val LIMIT_SHOWN_MS = 60_000L
        // Fraction of the gauge range the short-term peak falls per second once its hold ends.
        const val FALL_PER_S = 0.5
    }
}
