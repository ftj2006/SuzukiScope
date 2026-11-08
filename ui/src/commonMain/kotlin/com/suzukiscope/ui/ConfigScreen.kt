package com.suzukiscope.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.suzukiscope.core.field.FieldDefinition
import com.suzukiscope.core.elm327.Elm327Protocol
import com.suzukiscope.core.elm327.busProtocol
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import kotlinx.coroutines.launch

/**
 * Lets the user choose which configured fields appear on the dashboard, reorder them, and
 * set per-field caution/warning thresholds (used by the Gauge to change colour/show tick marks).
 * Field definitions themselves (request/decode formulas) come from [DashboardViewModel.registry]
 * — either the small verified default set, or the full extracted catalog after
 * [DashboardViewModel.loadFullCatalog].
 */
@Composable
fun ConfigScreen(viewModel: DashboardViewModel, modifier: Modifier = Modifier) {
    val fields by viewModel.fields.collectAsState()
    var filter by remember { mutableStateOf("") }
    var editingField by remember { mutableStateOf<FieldDefinition?>(null) }
    var debugLogOpen by remember { mutableStateOf(false) }
    var showNoDataFields by remember { mutableStateOf(false) }
    val vehicleProtocol by viewModel.vehicleProtocol.collectAsState()
    var protocolMenuOpen by remember { mutableStateOf(false) }
    var pendingProtocol by remember { mutableStateOf<Elm327Protocol?>(null) }
    val busFields = fields.filter { it.busProtocol == vehicleProtocol }
    val filtered = (if (filter.isBlank()) busFields else busFields.filter { it.label.contains(filter, ignoreCase = true) })
        .filter { showNoDataFields || !it.verifiedNoData }
    val visible = filtered.sortedByDescending { it.enabled }
    val debugLogsEnabled by viewModel.debugLogsEnabled.collectAsState()
    val testDataEnabled by viewModel.testDataEnabled.collectAsState()
    val fieldProbeStatus by viewModel.fieldProbeStatus.collectAsState()
    val noDataCount = fields.count { it.verifiedNoData }
    val coroutineScope = rememberCoroutineScope()

    editingField?.let { field ->
        ThresholdDialog(
            field = field,
            onDismiss = { editingField = null },
            onResetLearned = { viewModel.resetLearnedRange(field.id); editingField = null },
            onSave = { min, max, warning, critical ->
                min?.let { viewModel.setGaugeMin(field.id, it) }
                max?.let { viewModel.setGaugeMax(field.id, it) }
                viewModel.setThresholds(field.id, warning, critical)
                editingField = null
            },
        )
    }

    Column(modifier.fillMaxSize().padding(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Debug", style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
            Checkbox(checked = debugLogsEnabled, onCheckedChange = { viewModel.setDebugLogsEnabled(it) })
            Text("Logs", style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
            Checkbox(checked = testDataEnabled, onCheckedChange = { viewModel.setTestDataEnabled(it) })
            Text("Test", style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            IconButton(onClick = { debugLogOpen = true }, enabled = debugLogsEnabled) {
                androidx.compose.material3.Icon(Icons.Outlined.BugReport, contentDescription = "View connection logs")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Protocol", style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
            androidx.compose.foundation.layout.Box {
                TextButton(onClick = { protocolMenuOpen = true }) {
                    Text("${protocolName(vehicleProtocol)} \u25BE")
                }
                DropdownMenu(expanded = protocolMenuOpen, onDismissRequest = { protocolMenuOpen = false }) {
                    Elm327Protocol.entries.reversed().forEach { protocol ->
                        DropdownMenuItem(
                            text = { Text(protocolName(protocol)) },
                            onClick = {
                                protocolMenuOpen = false
                                if (protocol != vehicleProtocol) {
                                    if (fields.any { it.enabled }) pendingProtocol = protocol else viewModel.setVehicleProtocol(protocol)
                                }
                            },
                        )
                    }
                }
            }
        }
        Text(
            protocolHint(vehicleProtocol),
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
        )
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            label = { Text("Filter fields (${busFields.size} total, ${busFields.count { it.enabled }} shown)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Checkbox(checked = showNoDataFields, onCheckedChange = { showNoDataFields = it })
            Text(
                "Show $noDataCount field(s) with no data",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { coroutineScope.launch { viewModel.probeAllFieldsForData() } }) {
                Text("Test all fields")
            }
        }
        fieldProbeStatus?.let {
            Text(it, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 2.dp))
        }
        Row(modifier = Modifier.padding(top = 8.dp)) {
            Text("Display", style = androidx.compose.material3.MaterialTheme.typography.labelSmall, modifier = Modifier.width(48.dp))
            Text("Record", style = androidx.compose.material3.MaterialTheme.typography.labelSmall, modifier = Modifier.width(48.dp))
        }
        val density = LocalDensity.current
        val rowHeightPx = with(density) { 48.dp.toPx() }
        var draggingId by remember { mutableStateOf<String?>(null) }
        var dragOffsetY by remember { mutableStateOf(0f) }
        LazyColumn {
            items(visible, key = { it.id }) { field ->
                val isDragging = field.id == draggingId
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .height(48.dp)
                        .padding(vertical = 2.dp)
                        .graphicsLayer { translationY = if (isDragging) dragOffsetY else 0f }
                        .zIndex(if (isDragging) 1f else 0f),
                ) {
                    Checkbox(
                        checked = field.enabled,
                        onCheckedChange = { checked -> viewModel.setFieldEnabled(field.id, checked) },
                    )
                    Checkbox(
                        checked = field.recordEnabled,
                        onCheckedChange = { checked -> viewModel.setRecordEnabled(field.id, checked) },
                    )
                    Text(
                        "\u2261", // drag handle — press and drag up/down to reorder
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .width(28.dp)
                            .pointerInput(field.id) {
                                detectDragGestures(
                                    onDragStart = { draggingId = field.id; dragOffsetY = 0f },
                                    onDragEnd = {
                                        val shift = (dragOffsetY / rowHeightPx).let { if (it >= 0) kotlin.math.floor(it) else kotlin.math.ceil(it) }.toInt()
                                        if (shift != 0) viewModel.moveField(field.id, shift)
                                        draggingId = null
                                        dragOffsetY = 0f
                                    },
                                    onDragCancel = { draggingId = null; dragOffsetY = 0f },
                                ) { change, dragAmount ->
                                    change.consume()
                                    dragOffsetY += dragAmount.y
                                }
                            },
                    )
                    Text(
                        "${field.label} (${field.unit})",
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                    IconButton(onClick = { editingField = field }, modifier = Modifier.width(36.dp)) {
                        Text("\u26A0") // warning-triangle icon for threshold editing
                    }
                }
            }
        }
    }

    pendingProtocol?.let { protocol ->
        AlertDialog(
            onDismissRequest = { pendingProtocol = null },
            title = { Text("Switch to ${protocolName(protocol)}?") },
            text = {
                Text("The adapter can only read one protocol at a time, so your current ${protocolName(vehicleProtocol)} gauges will be removed from the dashboard.")
            },
            confirmButton = {
                TextButton(onClick = { viewModel.setVehicleProtocol(protocol); pendingProtocol = null }) { Text("Switch") }
            },
            dismissButton = { TextButton(onClick = { pendingProtocol = null }) { Text("Cancel") } },
        )
    }

    if (debugLogOpen) {
        AlertDialog(
            onDismissRequest = { debugLogOpen = false },
            title = { Text("Connection logs") },
            text = {
                Text(
                    viewModel.connectionLogsForExport().joinToString("\n\n") { log ->
                        "Connection ${log.id}\n${log.contents}"
                    }.ifEmpty { "No connection logs recorded." },
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { debugLogOpen = false }) { Text("Close") }
            },
        )
    }
}

private fun protocolName(protocol: Elm327Protocol): String = when (protocol) {
    Elm327Protocol.CAN_11BIT_500K -> "CAN"
    Elm327Protocol.KWP_FAST -> "K-Line (KWP)"
}

private fun protocolHint(protocol: Elm327Protocol): String = when (protocol) {
    Elm327Protocol.CAN_11BIT_500K -> "Newer Suzukis with CAN diagnostics. Only CAN fields are listed."
    Elm327Protocol.KWP_FAST -> "Older Suzukis with K-Line diagnostics. Only K-Line fields are listed."
}

/** Lets the user set/clear a field's gauge minimum/maximum (hidden for percentage fields, which
 * are always 0..100) and warning (mild) / critical (severe) thresholds. */
@Composable
private fun ThresholdDialog(
    field: FieldDefinition,
    onDismiss: () -> Unit,
    onResetLearned: () -> Unit,
    onSave: (min: Double?, max: Double?, warning: Double?, critical: Double?) -> Unit,
) {
    var minText by remember(field.id) { mutableStateOf(field.gaugeMin.toString()) }
    var maxText by remember(field.id) { mutableStateOf(field.gaugeMax.toString()) }
    var warningText by remember(field.id) { mutableStateOf(field.warningThreshold?.toString() ?: "") }
    var criticalText by remember(field.id) { mutableStateOf(field.criticalThreshold?.toString() ?: "") }
    val isPercentage = field.unit == "%"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${field.label} limits") },
        text = {
            Column {
                if (!isPercentage) {
                    OutlinedTextField(
                        value = minText,
                        onValueChange = { minText = it },
                        label = { Text("Minimum \u2014 ${field.unit}") },
                    )
                    OutlinedTextField(
                        value = maxText,
                        onValueChange = { maxText = it },
                        label = { Text("Maximum \u2014 ${field.unit}") },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                OutlinedTextField(
                    value = warningText,
                    onValueChange = { warningText = it },
                    label = { Text("Warning (mild) \u2014 ${field.unit}") },
                    modifier = Modifier.padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = criticalText,
                    onValueChange = { criticalText = it },
                    label = { Text("Critical \u2014 ${field.unit}") },
                )
                val learned = if (field.learnedMin != null && field.learnedMax != null) {
                    "Learned from your drives: ${"%.1f".format(field.learnedMin)} \u2013 ${"%.1f".format(field.learnedMax)} ${field.unit}"
                } else {
                    "No range learned yet \u2014 the scale adjusts as you drive."
                }
                Text(learned, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                if (field.learnedMin != null) {
                    TextButton(onClick = onResetLearned) { Text("Reset learned range") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    if (isPercentage) null else minText.toDoubleOrNull(),
                    if (isPercentage) null else maxText.toDoubleOrNull(),
                    warningText.toDoubleOrNull(),
                    criticalText.toDoubleOrNull(),
                )
            }) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
