package com.suzukiscope.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Scrollable list of recent-value line charts, one per enabled field — a lightweight, always-on
 * complement to the CSV logging/export flow (which only buffers while a session is recording). */
@Composable
fun HistoryScreen(viewModel: DashboardViewModel, modifier: Modifier = Modifier) {
    val fields by viewModel.fields.collectAsState()
    val history by viewModel.history.collectAsState()

    LazyColumn(modifier.fillMaxSize().padding(12.dp)) {
        items(fields.filter { it.enabled }, key = { it.id }) { field ->
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text("${field.label} (${field.unit})", style = MaterialTheme.typography.titleSmall)
                HistoryChart(
                    values = history[field.id].orEmpty(),
                    min = field.gaugeMin,
                    max = field.gaugeMax,
                    modifier = Modifier.fillMaxWidth().height(64.dp).padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun HistoryChart(values: List<Double>, min: Double, max: Double, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val range = (max - min).takeIf { it > 0 } ?: 1.0
        val stepX = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = size.height - (((v - min) / range).coerceIn(0.0, 1.0) * size.height).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = Color(0xFF4FC3F7), style = Stroke(width = 4f))
    }
}
