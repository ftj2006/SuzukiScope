package com.suzukiscan.core.session

import com.suzukiscan.core.field.FieldDefinition
import com.suzukiscan.core.log.Reading
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Source of decoded field values — implemented by a real adapter session or a simulator. */
interface LiveDataSource {
    suspend fun poll(field: FieldDefinition): Double
}

/**
 * Polls a set of enabled fields on a fixed interval and emits [Reading]s.
 * Field set is supplied dynamically (not hardcoded) so the UI can add/remove/toggle
 * fields while polling is running.
 */
class LiveDataSession(
    private val source: LiveDataSource,
    private val fieldsProvider: () -> List<FieldDefinition>,
    private val intervalMs: Long = 500,
) {
    fun readings(): Flow<Reading> = flow {
        while (true) {
            for (field in fieldsProvider().filter { it.enabled }) {
                // A single field glitching on real hardware (timeout, malformed frame) shouldn't
                // kill the whole polling loop - the source itself already logs the failure.
                try {
                    val value = source.poll(field)
                    emit(Reading(field.id, currentTimeMillis(), value))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // skip this reading; source logs details (e.g. Elm327LiveDataSource.ioLog)
                }
            }
            delay(intervalMs)
        }
    }
}

expect fun currentTimeMillis(): Long
