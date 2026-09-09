package com.suzukiscope.core.session

import com.suzukiscope.core.field.FieldDefinition
import kotlin.math.sin
import kotlin.random.Random

/**
 * Fake data source for UI development/testing without real hardware — produces a
 * plausible wandering value within each field's gauge range. Swap for a real
 * [LiveDataSource] backed by a [com.suzukiscope.core.transport.Transport] once the
 * ELM327/KWP2000 request round-trip is wired up.
 */
class SimulatedLiveDataSource : LiveDataSource {
    private val phase = HashMap<String, Double>()

    override suspend fun poll(field: FieldDefinition): Double {
        val t = (phase[field.id] ?: Random.nextDouble(0.0, 100.0)).let { it + 0.15 }
        phase[field.id] = t
        val mid = (field.gaugeMin + field.gaugeMax) / 2
        val amplitude = (field.gaugeMax - field.gaugeMin) / 2 * 0.6
        val noise = Random.nextDouble(-1.0, 1.0) * (field.gaugeMax - field.gaugeMin) * 0.02
        return (mid + amplitude * sin(t) + noise).coerceIn(field.gaugeMin, field.gaugeMax)
    }
}
