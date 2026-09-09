package com.suzukiscope.android.auto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.car.app.model.CarIcon
import androidx.core.graphics.drawable.IconCompat

/**
 * Renders a compact radial gauge as a bitmap for use as a Car App [GridItem]/[Row] image. The
 * Car App Library's templates (Row/Grid/Pane) can't host custom Compose drawing like the
 * phone's [com.suzukiscope.ui.Gauge] — the only way to get a gauge-like visual on Android Auto
 * is to pre-render one as a plain Android bitmap and attach it as the item's icon. The label,
 * value and max are all baked inside the arc's open centre — the GridItem itself has no title
 * of its own, since that space is better spent letting the ring fill the whole tile.
 */
object GaugeIcon {
    private const val SIZE_PX = 320
    private const val START_ANGLE = 135f
    private const val SWEEP_ANGLE = 270f

    fun render(
        label: String,
        unit: String,
        value: Double,
        min: Double,
        max: Double,
        peak: Double?,
        warningThreshold: Double?,
        criticalThreshold: Double?,
        decimals: Int? = null,
    ): CarIcon {
        val bitmap = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val stroke = SIZE_PX * 0.095f
        val inset = stroke / 2 + 4f
        val rect = RectF(inset, inset, SIZE_PX - inset, SIZE_PX - inset)
        val radius = (SIZE_PX - inset * 2) / 2f
        val centerX = SIZE_PX / 2f
        val centerY = SIZE_PX / 2f

        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            color = Color.argb(255, 70, 70, 70)
        }
        canvas.drawArc(rect, START_ANGLE, SWEEP_ANGLE, false, trackPaint)

        val fraction = ((value - min) / (max - min)).coerceIn(0.0, 1.0)
        val color = colorFor(fraction, value, warningThreshold, criticalThreshold)
        val valuePaint = Paint(trackPaint).apply { this.color = color }
        canvas.drawArc(rect, START_ANGLE, (SWEEP_ANGLE * fraction).toFloat(), false, valuePaint)

        if (peak != null) {
            val tickFraction = ((peak - min) / (max - min)).coerceIn(0.0, 1.0)
            val angle = Math.toRadians((START_ANGLE + SWEEP_ANGLE * tickFraction).toDouble())
            val inner = radius - stroke
            val outer = radius + stroke * 0.6f
            val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.WHITE
                strokeWidth = stroke * 0.28f
                strokeCap = Paint.Cap.ROUND
            }
            canvas.drawLine(
                centerX + inner * Math.cos(angle).toFloat(), centerY + inner * Math.sin(angle).toFloat(),
                centerX + outer * Math.cos(angle).toFloat(), centerY + outer * Math.sin(angle).toFloat(),
                tickPaint,
            )
        }

        val text = formatValue(value, decimals) + valueSuffix(unit)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textAlign = Paint.Align.CENTER
            textSize = SIZE_PX * (if (text.length > 4) 0.24f else 0.31f)
            isFakeBoldText = true
        }
        val valueBaselineY = centerY + textPaint.textSize * 0.15f
        canvas.drawText(text, centerX, valueBaselineY, textPaint)

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.argb(210, 190, 190, 190)
            textAlign = Paint.Align.CENTER
            textSize = SIZE_PX * 0.09f
        }
        canvas.drawText(shortLabel(label), centerX, valueBaselineY - textPaint.textSize * 0.85f - labelPaint.textSize * 0.3f, labelPaint)

        val maxText = "max ${formatValue(peak ?: max, decimals)}${valueSuffix(unit)}"
        val maxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.argb(200, 190, 190, 190)
            textAlign = Paint.Align.CENTER
            textSize = SIZE_PX * 0.09f
        }
        canvas.drawText(maxText, centerX, valueBaselineY + textPaint.textSize * 0.42f + maxPaint.textSize * 0.9f, maxPaint)

        return CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
    }

    /** Short symbol appended straight onto the value instead of a separate unit label, e.g. "45\u00b0". */
    private fun valueSuffix(unit: String): String = when (unit) {
        "\u00b0C" -> "\u00b0"
        "%" -> "%"
        "V" -> "V"
        else -> ""
    }

    /** A few common labels shortened to fit the gauge; anything else is just truncated. */
    private val SHORT_LABELS = mapOf(
        "Engine RPM" to "RPM",
        "Oil Temperature" to "Oil",
        "Water Temperature" to "Water",
        "Boost Pressure" to "Boost",
        "Throttle Position" to "TPS",
        "Battery Voltage" to "Batt",
    )

    fun shortLabel(label: String): String = SHORT_LABELS[label] ?: label.take(8)

    /**
     * Wi-Fi-signal glyph for the connect/disconnect ActionStrip action — a distinct silhouette
     * from [loggingStatusIcon]'s plain dot, since the car host re-tints action icons to a single
     * colour (custom RGB hues don't survive), so shape is the only reliable way to tell the two
     * ActionStrip buttons apart at a glance. A diagonal slash marks "disconnected", matching the
     * familiar "no Wi-Fi" convention rather than relying on subtler alpha/boldness differences.
     */
    fun connectionStatusIcon(connected: Boolean): CarIcon {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = size / 2f
        val cy = size * 0.6f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = size * 0.1f
            strokeCap = Paint.Cap.ROUND
            color = Color.WHITE
        }
        canvas.drawArc(RectF(cx - size * 0.42f, cy - size * 0.74f, cx + size * 0.42f, cy + size * 0.1f), 205f, 130f, false, paint)
        canvas.drawArc(RectF(cx - size * 0.24f, cy - size * 0.46f, cx + size * 0.24f, cy + size * 0.02f), 205f, 130f, false, paint)
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        canvas.drawCircle(cx, cy, size * 0.075f, dotPaint)
        if (!connected) {
            val slashPaint = Paint(paint).apply { strokeWidth = size * 0.09f }
            canvas.drawLine(size * 0.14f, size * 0.14f, size * 0.86f, size * 0.86f, slashPaint)
        }
        return CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
    }

    /** Small icon-only status indicator (filled = recording, hollow ring = not) for the ActionStrip. */
    fun loggingStatusIcon(logging: Boolean): CarIcon = dotIcon(logging, Color.WHITE)

    private fun dotIcon(active: Boolean, activeColor: Int): CarIcon {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (active) activeColor else Color.argb(255, 120, 120, 120)
            style = if (active) Paint.Style.FILL else Paint.Style.STROKE
            strokeWidth = size * 0.12f
        }
        val margin = size * 0.15f
        canvas.drawOval(margin, margin, size - margin, size - margin, paint)
        return CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
    }

    private fun formatValue(v: Double, decimals: Int?): String = when {
        decimals != null -> "%.${decimals}f".format(v)
        v == v.toLong().toDouble() -> v.toLong().toString()
        else -> "%.1f".format(v)
    }

    private fun colorFor(fraction: Double, value: Double, warningThreshold: Double?, criticalThreshold: Double?): Int = when {
        criticalThreshold != null && value >= criticalThreshold -> Color.rgb(0xE5, 0x39, 0x35)
        warningThreshold != null && value >= warningThreshold -> Color.rgb(0xFF, 0xB3, 0x00)
        warningThreshold != null || criticalThreshold != null -> Color.rgb(0x43, 0xA0, 0x47)
        else -> when {
            fraction > 0.85 -> Color.rgb(0xE5, 0x39, 0x35)
            fraction > 0.65 -> Color.rgb(0xFF, 0xB3, 0x00)
            else -> Color.rgb(0x43, 0xA0, 0x47)
        }
    }
}
