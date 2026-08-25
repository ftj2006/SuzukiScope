package com.suzukiscan.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

/**
 * A circular gauge showing the current value, a coloured arc for min..max, a tick mark for
 * the recorded peak value, and (if set) tick marks + colour changes at the caution/warning
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
    cautionThreshold: Double? = null,
    warningThreshold: Double? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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

                val fraction = ((value - min) / (max - min)).coerceIn(0.0, 1.0)
                drawArc(
                    color = gaugeColor(fraction, value, cautionThreshold, warningThreshold),
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

                cautionThreshold?.let { tick(it, Color(0xFFFFB300), 0.2f) }
                warningThreshold?.let { tick(it, Color(0xFFE53935), 0.2f) }
                peak?.let { tick(it, Color(0xFFFF5252), 0.25f) }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val valueColor = gaugeColor(
                    ((value - min) / (max - min)).coerceIn(0.0, 1.0),
                    value,
                    cautionThreshold,
                    warningThreshold,
                )
                Text(
                    formatValue(value),
                    style = androidx.compose.material3.MaterialTheme.typography.headlineMedium,
                    color = valueColor,
                )
                Text(unit, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            }
        }
        Text(label, style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
        peak?.let { Text("peak: ${formatValue(it)} $unit", style = androidx.compose.material3.MaterialTheme.typography.labelSmall) }
    }
}

/** Colours by caution/warning thresholds when set, else falls back to the min..max fraction. */
private fun gaugeColor(fraction: Double, value: Double, cautionThreshold: Double?, warningThreshold: Double?): Color = when {
    warningThreshold != null && value >= warningThreshold -> Color(0xFFE53935)
    cautionThreshold != null && value >= cautionThreshold -> Color(0xFFFFB300)
    cautionThreshold != null || warningThreshold != null -> Color(0xFF43A047)
    else -> when {
        fraction > 0.85 -> Color(0xFFE53935)
        fraction > 0.65 -> Color(0xFFFFB300)
        else -> Color(0xFF43A047)
    }
}

private fun formatValue(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)
