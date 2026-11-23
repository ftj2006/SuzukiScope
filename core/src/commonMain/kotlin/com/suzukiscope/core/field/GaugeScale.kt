package com.suzukiscope.core.field

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round

/**
 * Gauge range for a learned [min]..[max], rounded outwards to half-decade steps sized to the
 * values (e.g. 96 -> 100 in 5s, 6420 -> 6500 in 500s, 1.55 -> 2.0 in 0.5s), always tall enough
 * to show any [thresholds] and at least two steps wide so a narrow early range isn't jumpy.
 */
fun learnedGaugeRange(
    min: Double,
    max: Double,
    thresholds: List<Double> = emptyList(),
    stepSize: Double? = null,
): Pair<Double, Double> {
    val top = (thresholds + max).max()
    // Step from the span, not the absolute value, so 84..120 rounds in 5s rather than 50s.
    val magnitude = (top - min).takeIf { it > 0.0 }
        ?: maxOf(kotlin.math.abs(min), kotlin.math.abs(top)).takeIf { it > 0.0 }
        ?: 1.0
    val step = stepSize?.takeIf { it > 0.0 } ?: 10.0.pow(floor(log10(magnitude))) / 2
    val lo = floorToStep(min, step)
    val hi = maxOf(ceilToStep(top, step), lo + 2 * step)
    return lo to hi
}

private fun floorToStep(value: Double, step: Double): Double {
    val units = value / step
    val nearest = round(units)
    val rounded = (if (kotlin.math.abs(units - nearest) < 1e-9) nearest else floor(units)) * step
    return round(rounded * 1e10) / 1e10
}

private fun ceilToStep(value: Double, step: Double): Double {
    val units = value / step
    val nearest = round(units)
    val rounded = (if (kotlin.math.abs(units - nearest) < 1e-9) nearest else ceil(units)) * step
    return round(rounded * 1e10) / 1e10
}

/**
 * Rounds [value] up to a "nice" round number for a gauge's top-of-scale, at a step size
 * scaled to its magnitude (order of magnitude minus one) so it always lands on a clean
 * number rather than the exact reading — e.g. 6420 -> 6500 (nearest 100), 1.55 -> 1.6
 * (nearest 0.1), 105 -> 110 (nearest 10).
 */
fun niceScaleMax(value: Double): Double {
    if (value <= 0.0) return value
    val step = 10.0.pow(floor(log10(value)) - 1)
    return ceil(value / step) * step
}

/**
 * Mirrors [niceScaleMax] but rounds down for a gauge's bottom-of-scale, at a step size scaled
 * to the value's magnitude — e.g. -6420 -> -6500 (nearest 100), 105 -> 100 (nearest 10).
 */
fun niceScaleMin(value: Double): Double {
    if (value == 0.0) return value
    val magnitude = kotlin.math.abs(value)
    val step = 10.0.pow(floor(log10(magnitude)) - 1)
    return floor(value / step) * step
}
