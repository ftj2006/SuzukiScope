package com.suzukiscope.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.FilledIconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Grid of gauges for all enabled fields, plus start/stop logging + export controls. */
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onExport: (files: List<DashboardViewModel.ExportFile>) -> Unit,
    connectionBar: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val fields by viewModel.fields.collectAsState()
    val values by viewModel.values.collectAsState()
    val peaks by viewModel.peaks.collectAsState()
    val staleFields by viewModel.staleFields.collectAsState()
    val isLogging by viewModel.isLogging.collectAsState()

    Column(modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(Modifier.weight(1f)) { connectionBar() }
            // Plain (unfilled) until actually recording, so it doesn't look pre-selected when idle.
            if (isLogging) {
                val pulse by rememberInfiniteTransition(label = "recording").animateFloat(
                    initialValue = 1f,
                    targetValue = 0.3f,
                    animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
                    label = "recordingPulse",
                )
                FilledIconButton(
                    onClick = { viewModel.stopLogging() },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error.copy(alpha = pulse),
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text("\u23F9") // stop icon
                }
            } else {
                IconButton(onClick = { viewModel.startLogging() }) {
                    Text("\u23FA") // record icon
                }
            }
            if (isLogging) {
                Text(
                    "${viewModel.recorder.readings().size} samples",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            IconButton(onClick = { onExport(emptyList()) }) {
                androidx.compose.material3.Icon(Icons.Outlined.FileDownload, contentDescription = "Export")
            }
        }
        val ecuTables by viewModel.ecuTables.collectAsState()
        VehicleRow(com.suzukiscope.core.ecu.EcuTables.summarise(ecuTables))
        val enabledFields = fields.filter { it.enabled }
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            // Spread gauges evenly over the rows they need (6 across a 5-wide screen -> 3 + 3, not 5 + 1).
            val maxColumns = (maxWidth / 160.dp).toInt().coerceAtLeast(1)
            val rows = (enabledFields.size + maxColumns - 1) / maxColumns
            val columns = if (rows == 0) 1 else (enabledFields.size + rows - 1) / rows
            LazyVerticalGrid(columns = GridCells.Fixed(columns), modifier = Modifier.fillMaxSize()) {
                items(enabledFields, key = { it.id }) { field ->
                    Gauge(
                        label = field.label,
                        unit = field.unit,
                        value = values[field.id] ?: field.gaugeMin,
                        min = field.gaugeMin,
                        max = field.gaugeMax,
                        peak = peaks[field.id],
                        warningThreshold = field.warningThreshold,
                        criticalThreshold = field.criticalThreshold,
                        decimals = field.decimals,
                        stale = field.id in staleFields,
                    )
                }
            }
        }
    }

}

/** Collapsible line of values the engine ECU stores (from the last snapshot); hidden until one has been read. */
@Composable
private fun VehicleRow(summary: com.suzukiscope.core.ecu.EcuTables.VehicleSummary) {
    if (summary.odometerKm == null && summary.engineStarts == null && summary.learnedTrims.isEmpty() && summary.latestFault == null) return
    var expanded by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 4.dp),
    ) {
        Text(
            (if (expanded) "\u25BE " else "\u25B8 ") + "Vehicle" +
                (summary.odometerKm?.let { ": %,d km".format(it) } ?: ""),
            style = MaterialTheme.typography.labelLarge,
        )
        if (expanded) {
            val style = MaterialTheme.typography.bodySmall
            summary.engineStarts?.let { Text("Engine starts: %,d".format(it), style = style) }
            if (summary.learnedTrims.isNotEmpty()) {
                Text("Learned fuel trims: " + summary.learnedTrims.joinToString(" / ") { "%+.1f%%".format(it) }, style = style)
            }
            Text(
                "Latest stored fault: " + (summary.latestFault?.let { f ->
                    f.code + (f.odometerKm?.let { " at %,d km".format(it) } ?: "")
                } ?: "none"),
                style = style,
            )
        }
    }
}
