package com.suzukiscope.android.auto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.car.app.model.CarIcon
import androidx.core.graphics.drawable.IconCompat
import com.suzukiscope.core.log.Reading
import kotlin.math.max

/** Wide graph image for the Pane detail screen; Android Auto still chooses its final bounds. */
internal object GaugeHistoryIcon {
    private const val WIDTH = 960
    private const val HEIGHT = 540
    private const val WINDOW_MS = 30_000L
    private const val LEFT = 110f
    private const val RIGHT = 930f
    private const val TOP = 42f
    private const val BOTTOM = 438f

    fun render(points: List<Reading>, unit: String, nowMs: Long): CarIcon {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(24, 29, 34))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val windowStart = nowMs - WINDOW_MS
        val visible = points.filter { it.timestampMs in windowStart..nowMs }
        val values = visible.map { it.value }
        val dataMin = values.minOrNull() ?: 0.0
        val dataMax = values.maxOrNull() ?: 1.0
        val span = (dataMax - dataMin).takeIf { it > 0.0 } ?: max(1.0, kotlin.math.abs(dataMax) * 0.1)
        val low = dataMin - span * 0.1
        val high = dataMax + span * 0.1
        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(69, 78, 86)
            strokeWidth = 2f
        }
        for (i in 0..4) {
            val y = TOP + (BOTTOM - TOP) * i / 4f
            canvas.drawLine(LEFT, y, RIGHT, y, grid)
        }
        val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(193, 201, 207)
            textSize = 30f
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText(format(high), LEFT - 16f, TOP + 10f, axis)
        canvas.drawText(format(low), LEFT - 16f, BOTTOM + 10f, axis)
        axis.textAlign = Paint.Align.LEFT
        canvas.drawText("-30 s", LEFT, 505f, axis)
        axis.textAlign = Paint.Align.RIGHT
        canvas.drawText("Now", RIGHT, 505f, axis)
        val unitPaint = Paint(axis).apply { textAlign = Paint.Align.LEFT }
        canvas.drawText(unit, LEFT, 30f, unitPaint)

        if (visible.size >= 2 && high > low) {
            val path = Path()
            visible.forEachIndexed { index, reading ->
                val x = LEFT + (reading.timestampMs - windowStart).toFloat() / WINDOW_MS * (RIGHT - LEFT)
                val y = BOTTOM - ((reading.value - low) / (high - low)).toFloat() * (BOTTOM - TOP)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(79, 195, 247)
                strokeWidth = 8f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                style = Paint.Style.STROKE
            }
            canvas.drawPath(path, line)
            val latest = visible.last()
            val x = LEFT + (latest.timestampMs - windowStart).toFloat() / WINDOW_MS * (RIGHT - LEFT)
            val y = BOTTOM - ((latest.value - low) / (high - low)).toFloat() * (BOTTOM - TOP)
            paint.color = Color.WHITE
            canvas.drawCircle(x, y, 11f, paint)
        } else {
            paint.color = Color.rgb(193, 201, 207)
            paint.textSize = 34f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("Collecting recent readings", WIDTH / 2f, HEIGHT / 2f, paint)
        }
        return CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
    }

    private fun format(value: Double): String = "%.1f".format(value)
}
