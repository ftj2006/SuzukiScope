package com.suzukiscope.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private const val START_ANGLE = 135f
private const val SWEEP_ANGLE = 270f
private val STALE_COLOR = Color(0xFF757575)
private val SHORT_PEAK_COLOR = Color(0xFF4FC3F7)
private const val SHORT_PEAK_HOLD_MS = 1500L
// Fraction of the gauge range the short-term peak falls per second once its hold ends.
private const val SHORT_PEAK_FALL_PER_S = 0.5

/** Peak-meter style hold: follows rises at once, holds, then falls back to the live value. */
private class ShortTermPeak {
    private var peak = Double.NaN
    private var heldAtMs = 0L
    private var lastMs = 0L

    fun update(value: Double, range: Double, nowMs: Long): Double {
        if (peak.isNaN() || value >= peak) {
            peak = value
            heldAtMs = nowMs
        } else if (nowMs - heldAtMs > SHORT_PEAK_HOLD_MS) {
            peak = maxOf(value, peak - range * SHORT_PEAK_FALL_PER_S * (nowMs - lastMs) / 1000.0)
        }
        lastMs = nowMs
        return peak
    }
}

/**
 * A circular gauge showing the current value, a coloured arc for min..max, a tick mark for
 * the recorded peak value, a blue short-term peak that holds then falls back, and (if set) tick marks + colour changes at the warning/critical
 * thresholds instead of the generic min/max-fraction colouring.
 */
@Composable
fun Gauge(
    label: String,
    unit: String,
    value: Double,
    min: Double,
    max: Double,
    peak: Double? = null,
    warningThreshold: Double? = null,
    criticalThreshold: Double? = null,
    decimals: Int? = null,
    /** No recent reading: drawn grey so a frozen feed isn't mistaken for a steady value. */
    stale: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // A critically damped spring follows rapid new readings smoothly; a restarted tween kept
        // re-entering its slow ease-in and barely moved. 4x StiffnessHigh settles in ~25 ms (was ~50).
        val shownValue by animateFloatAsState(
            value.toFloat(),
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 4 * Spring.StiffnessHigh),
            label = "gaugeValue",
        )
        val latestValue by rememberUpdatedState(value)
        val shortTermPeak = remember { ShortTermPeak() }
        var shortPeak by remember { mutableStateOf(value) }
        LaunchedEffect(min, max) {
            while (true) {
                withFrameMillis { now -> shortPeak = shortTermPeak.update(latestValue, max - min, now) }
            }
        }
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
                val stroke = size.minDimension * 0.09f
                val radius = (min(size.width, size.height) - stroke) / 2
                val center = Offset(size.width / 2, size.height / 2)
                val topLeft = Offset(center.x - radius, center.y - radius)
                val arcSize = androidx.compose.ui.geometry.Size(radius * 2, radius * 2)

                drawArc(
                    color = Color(0xFF3A3A3A),
                    startAngle = START_ANGLE,
                    sweepAngle = SWEEP_ANGLE,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )

                val fraction = ((shownValue - min) / (max - min)).coerceIn(0.0, 1.0)
                drawArc(
                    color = if (stale) STALE_COLOR else gaugeColor(value, warningThreshold, criticalThreshold),
                    startAngle = START_ANGLE,
                    sweepAngle = (SWEEP_ANGLE * fraction).toFloat(),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )

                fun tick(at: Double, color: Color, widthFactor: Float) {
                    val tickFraction = ((at - min) / (max - min)).coerceIn(0.0, 1.0)
                    val angle = Math.toRadians((START_ANGLE + SWEEP_ANGLE * tickFraction).toDouble())
                    val inner = radius - stroke
                    val outer = radius + stroke * 0.6f
                    drawLine(
                        color = color,
                        start = Offset(center.x + inner * cos(angle).toFloat(), center.y + inner * sin(angle).toFloat()),
                        end = Offset(center.x + outer * cos(angle).toFloat(), center.y + outer * sin(angle).toFloat()),
                        strokeWidth = stroke * widthFactor,
                        cap = StrokeCap.Round,
                    )
                }

                warningThreshold?.let { tick(it, Color(0xFFFFB300).copy(alpha = 0.55f), 0.1f) }
                criticalThreshold?.let { tick(it, Color(0xFFE53935).copy(alpha = 0.55f), 0.1f) }
                peak?.let { tick(it, Color(0xFFFFFFFF), 0.28f) }
                // Hidden while it sits on the needle, so it only shows a recent high point.
                if (!stale && shortPeak > shownValue + (max - min) * 0.01) tick(shortPeak, SHORT_PEAK_COLOR, 0.18f)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // White digits for readability; only warning/critical recolour the number.
                val valueColor = when {
                    stale -> STALE_COLOR
                    criticalThreshold != null && value >= criticalThreshold -> Color(0xFFE53935)
                    warningThreshold != null && value >= warningThreshold -> Color(0xFFFFB300)
                    else -> Color.White
                }
                Text(
                    formatValue(value, decimals),
                    // Tabular digits keep the number from shifting as individual digits change.
                    style = androidx.compose.material3.MaterialTheme.typography.headlineMedium
                        .copy(fontFeatureSettings = "tnum", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                    color = valueColor,
                )
                Text(unit, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                peak?.let {
                    Text(
                        "Peak ${formatValue(it, decimals)}",
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    )
                }
            }
            // Scale ends under the two ends of the arc, like the markings on a real gauge.
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = 24.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            ) {
                val scaleStyle = androidx.compose.material3.MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum")
                Text(formatValue(min, decimals), style = scaleStyle, color = Color(0xFFBDBDBD))
                Text(formatValue(max, decimals), style = scaleStyle, color = Color(0xFFBDBDBD))
            }
        }
        Text(label, style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
    }
}

/** Colours by warning/critical thresholds; without any, the gauge stays green so orange/red always mean a set limit. */
private fun gaugeColor(value: Double, warningThreshold: Double?, criticalThreshold: Double?): Color = when {
    criticalThreshold != null && value >= criticalThreshold -> Color(0xFFE53935)
    warningThreshold != null && value >= warningThreshold -> Color(0xFFFFB300)
    else -> Color(0xFF43A047)
}

private fun formatValue(v: Double, decimals: Int?): String = when {
    decimals != null -> "%.${decimals}f".format(v)
    v == v.toLong().toDouble() -> v.toLong().toString()
    else -> "%.1f".format(v)
}
