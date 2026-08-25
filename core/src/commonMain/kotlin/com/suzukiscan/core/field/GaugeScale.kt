package com.suzukiscan.core.field

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

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
