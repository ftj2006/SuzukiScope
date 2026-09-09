package com.suzukiscope.ui

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
    val isLogging by viewModel.isLogging.collectAsState()

    Column(modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(Modifier.weight(1f)) { connectionBar() }
            // Plain (unfilled) until actually recording, so it doesn't look pre-selected when idle.
            if (isLogging) {
                FilledIconButton(
                    onClick = { viewModel.stopLogging() },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error,
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
        LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 160.dp), modifier = Modifier.fillMaxSize()) {
            items(fields.filter { it.enabled }, key = { it.id }) { field ->
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
                )
            }
        }
    }

}
