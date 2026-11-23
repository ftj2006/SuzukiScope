package com.suzukiscope.android.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.model.Action
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.suzukiscope.android.AppState
import com.suzukiscope.core.log.Reading
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val HISTORY_WINDOW_MS = 30_000L

class GaugeHistoryScreen(carContext: CarContext, private val fieldId: String) : Screen(carContext) {
    private val viewModel = AppState.dashboardViewModel

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                lifecycleScope.launch {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        while (true) {
                            delay(1000)
                            invalidate()
                        }
                    }
                }
            }
        })
    }

    override fun onGetTemplate(): Template {
        val field = viewModel.registry.get(fieldId)
            ?: return PaneTemplate.Builder(Pane.Builder().addRow(Row.Builder().setTitle("Field unavailable").build()).build())
                .setTitle("Gauge history")
                .setHeaderAction(Action.BACK)
                .build()
        val now = com.suzukiscope.core.session.currentTimeMillis()
        val points = viewModel.timestampedHistory.value[fieldId].orEmpty()
            .filter { it.timestampMs >= now - HISTORY_WINDOW_MS }
        val recent = points.map(Reading::value)
        val current = viewModel.values.value[fieldId]?.let { format(it, field.unit) } ?: "Waiting for a reading"
        val range = if (recent.isEmpty()) "Waiting for history" else {
            "${format(recent.minOrNull()!!, field.unit)} to ${format(recent.maxOrNull()!!, field.unit)}"
        }
        val pane = Pane.Builder()
            .setImage(GaugeHistoryIcon.render(points, field.unit, now))
            .addRow(Row.Builder().setTitle("Current").addText(current).build())
            .addRow(Row.Builder().setTitle("Recent range").addText(range).build())
            .addRow(Row.Builder().setTitle("Window").addText("Last 30 seconds · ${points.size} readings").build())
            .addAction(Action.Builder()
                .setTitle("Minimise")
                .setOnClickListener { carContext.getCarService(ScreenManager::class.java).popToRoot() }
                .build())
            .build()
        return PaneTemplate.Builder(pane)
            .setTitle(field.label)
            .setHeaderAction(Action.BACK)
            .build()
    }

    private fun format(value: Double, unit: String): String = when (unit) {
        "%" -> "%.0f%%".format(value)
        "bar" -> "%.2f bar".format(value)
        "rpm" -> "%.0f rpm".format(value)
        "km/h" -> "%.0f km/h".format(value)
        else -> "%.1f%s".format(value, if (unit.isBlank()) "" else " $unit")
    }
}
