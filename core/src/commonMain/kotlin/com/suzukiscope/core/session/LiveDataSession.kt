package com.suzukiscope.core.session

import com.suzukiscope.core.field.FieldDefinition
import com.suzukiscope.core.field.RefreshRate
import com.suzukiscope.core.field.effectiveRefresh
import com.suzukiscope.core.log.Reading
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Source of decoded field values — implemented by a real adapter session or a simulator. */
interface LiveDataSource {
    suspend fun poll(field: FieldDefinition): Double

    /**
     * Called once at the start of each pass with the fields due in it, so implementations can
     * discard per-cycle caches and batch requests for those fields. No-op by default.
     */
    fun beginCycle(fields: List<FieldDefinition>) {}

    /** False for fields this source can't read right now (e.g. they need the other bus protocol); those are skipped. */
    fun canPoll(field: FieldDefinition): Boolean = true
}

/**
 * Polls the enabled fields and emits [Reading]s: [RefreshRate.FAST] fields every cycle,
 * [RefreshRate.SLOW] ones every [slowIntervalMs]. Field set is supplied dynamically so the UI
 * can add/remove/toggle fields while polling is running.
 */
class LiveDataSession(
    private val source: LiveDataSource,
    private val fieldsProvider: () -> List<FieldDefinition>,
    private val intervalMs: Long = 40,
    private val slowIntervalMs: Long = 3000,
) {
    fun readings(): Flow<Reading> = flow {
        val failures = HashMap<String, Int>()
        val retryAt = HashMap<String, Long>()
        var nextSlowAt = 0L
        while (true) {
            val now = currentTimeMillis()
            val includeSlow = now >= nextSlowAt
            if (includeSlow) nextSlowAt = now + slowIntervalMs
            val due = fieldsProvider().filter {
                source.canPoll(it) &&
                    (includeSlow || it.effectiveRefresh == RefreshRate.FAST) &&
                    now >= (retryAt[it.id] ?: 0L)
            }
            source.beginCycle(due)
            for (field in due) {
                // A single field glitching on real hardware (timeout, malformed frame) shouldn't
                // kill the whole polling loop - the source itself already logs the failure.
                try {
                    val value = source.poll(field)
                    failures.remove(field.id)
                    retryAt.remove(field.id)
                    emit(Reading(field.id, currentTimeMillis(), value))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Back off only this field; waiting here would stall every other field too.
                    val failureCount = (failures[field.id] ?: 0).coerceAtMost(5)
                    failures[field.id] = failureCount + 1
                    retryAt[field.id] = currentTimeMillis() + 250L * (1L shl failureCount)
                }
            }
            // With nothing to poll, a zero interval would spin.
            delay(if (due.isEmpty()) 100 else intervalMs)
        }
    }
}

expect fun currentTimeMillis(): Long
