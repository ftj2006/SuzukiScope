package com.suzukiscope.core.session

import com.suzukiscope.core.field.FieldDefinition
import com.suzukiscope.core.log.Reading
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Source of decoded field values — implemented by a real adapter session or a simulator. */
interface LiveDataSource {
    suspend fun poll(field: FieldDefinition): Double

    /**
     * Called once at the start of each full pass over the enabled fields, so implementations can
     * discard any per-cycle caches (e.g. a shared request/response reused across fields that ask
     * for the same underlying data). No-op by default.
     */
    fun beginCycle() {}
}

/**
 * Polls a set of enabled fields on a fixed interval and emits [Reading]s.
 * Field set is supplied dynamically (not hardcoded) so the UI can add/remove/toggle
 * fields while polling is running.
 */
class LiveDataSession(
    private val source: LiveDataSource,
    private val fieldsProvider: () -> List<FieldDefinition>,
    private val intervalMs: Long = 40,
) {
    fun readings(): Flow<Reading> = flow {
        val failures = HashMap<String, Int>()
        while (true) {
            source.beginCycle()
            for (field in fieldsProvider()) {
                // A single field glitching on real hardware (timeout, malformed frame) shouldn't
                // kill the whole polling loop - the source itself already logs the failure.
                try {
                    val value = source.poll(field)
                    failures.remove(field.id)
                    emit(Reading(field.id, currentTimeMillis(), value))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // skip this reading; source logs details (e.g. Elm327LiveDataSource.ioLog)
                    val failureCount = (failures[field.id] ?: 0).coerceAtMost(5)
                    failures[field.id] = failureCount + 1
                    delay(250L * (1L shl failureCount))
                }
            }
            delay(intervalMs)
        }
    }
}

expect fun currentTimeMillis(): Long
