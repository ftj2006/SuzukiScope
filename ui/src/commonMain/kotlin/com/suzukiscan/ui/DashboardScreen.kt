package com.suzukiscan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.FilledIconButton
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
    onExport: (String) -> Unit,
    onExportLog: (String) -> Unit = {},
    connectionBar: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val fields by viewModel.fields.collectAsState()
    val values by viewModel.values.collectAsState()
    val peaks by viewModel.peaks.collectAsState()
    val isLogging by viewModel.isLogging.collectAsState()

    Column(modifier.fillMaxSize().padding(12.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            connectionBar()
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            FilledIconButton(
                onClick = { if (isLogging) viewModel.stopLogging() else viewModel.startLogging() },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (isLogging) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    contentColor = if (isLogging) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text(if (isLogging) "\u23F9" else "\u23FA") // stop / record icon
            }
            if (isLogging) {
                Text(
                    "${viewModel.recorder.readings().size} samples",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            IconButton(onClick = { onExportLog(viewModel.exportIoLog()) }) {
                Text("\uD83D\uDCCB") // clipboard "connection log" icon
            }
            IconButton(onClick = { onExport(viewModel.exportCsv()) }) {
                Text("\uD83D\uDCBE") // floppy-disk "export/save" icon
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
                    cautionThreshold = field.cautionThreshold,
                    warningThreshold = field.warningThreshold,
                )
            }
        }
    }
}
