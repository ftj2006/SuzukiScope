package com.suzukiscan.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.suzukiscan.core.field.FieldDefinition

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
    val visible = if (filter.isBlank()) fields else fields.filter { it.label.contains(filter, ignoreCase = true) }

    editingField?.let { field ->
        ThresholdDialog(
            field = field,
            onDismiss = { editingField = null },
            onSave = { max, caution, warning ->
                max?.let { viewModel.setGaugeMax(field.id, it) }
                viewModel.setThresholds(field.id, caution, warning)
                editingField = null
            },
        )
    }

    Column(modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            label = { Text("Filter fields (${fields.size} total, ${fields.count { it.enabled }} shown)") },
            modifier = Modifier.fillMaxWidth(),
        )
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
}

/** Lets the user set/clear a field's gauge maximum (hidden for percentage fields, which are
 * always 0..100) and caution (mild) / warning (severe) thresholds. */
@Composable
private fun ThresholdDialog(
    field: FieldDefinition,
    onDismiss: () -> Unit,
    onSave: (max: Double?, caution: Double?, warning: Double?) -> Unit,
) {
    var maxText by remember(field.id) { mutableStateOf(field.gaugeMax.toString()) }
    var cautionText by remember(field.id) { mutableStateOf(field.cautionThreshold?.toString() ?: "") }
    var warningText by remember(field.id) { mutableStateOf(field.warningThreshold?.toString() ?: "") }
    val isPercentage = field.unit == "%"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${field.label} limits") },
        text = {
            Column {
                if (!isPercentage) {
                    OutlinedTextField(
                        value = maxText,
                        onValueChange = { maxText = it },
                        label = { Text("Maximum \u2014 ${field.unit}") },
                    )
                }
                OutlinedTextField(
                    value = cautionText,
                    onValueChange = { cautionText = it },
                    label = { Text("Caution (mild) \u2014 ${field.unit}") },
                )
                OutlinedTextField(
                    value = warningText,
                    onValueChange = { warningText = it },
                    label = { Text("Warning \u2014 ${field.unit}") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    if (isPercentage) null else maxText.toDoubleOrNull(),
                    cautionText.toDoubleOrNull(),
                    warningText.toDoubleOrNull(),
                )
            }) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
