package com.suzukiscope.core.session

import com.suzukiscope.core.field.FieldDefinition
import kotlin.math.exp
import kotlin.random.Random

/**
 * Fake data source for UI development/testing without real hardware. Each field eases towards
 * a target that changes every few seconds, so values rise and fall gradually like a real engine
 * rather than jumping around.
 */
class SimulatedLiveDataSource : LiveDataSource {
    private class State(var value: Double, var target: Double, var nextTargetAt: Long, var lastAt: Long)

    private val states = HashMap<String, State>()

    override suspend fun poll(field: FieldDefinition): Double {
        val now = currentTimeMillis()
        val range = field.gaugeMax - field.gaugeMin
        val state = states.getOrPut(field.id) {
            val start = field.gaugeMin + range * Random.nextDouble(0.2, 0.4)
            State(start, start, now, now)
        }
        if (now >= state.nextTargetAt) {
            state.target = field.gaugeMin + range * Random.nextDouble(0.15, 0.85)
            state.nextTargetAt = now + Random.nextLong(3_000, 10_000)
        }
        val dtSeconds = ((now - state.lastAt) / 1000.0).coerceIn(0.0, 0.5)
        state.lastAt = now
        state.value += (state.target - state.value) * (1 - exp(-dtSeconds / TIME_CONSTANT_SECONDS))
        val noise = Random.nextDouble(-1.0, 1.0) * range * 0.003
        return (state.value + noise).coerceIn(field.gaugeMin, field.gaugeMax)
    }

    private companion object {
        const val TIME_CONSTANT_SECONDS = 1.5
    }
}
