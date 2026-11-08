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
import com.suzukiscope.core.layout.AutoLayoutConfig
import com.suzukiscope.core.layout.AutoRow
import com.suzukiscope.core.layout.Placement
import com.suzukiscope.core.layout.TileSize
import com.suzukiscope.core.layout.middleOutOrder
import com.suzukiscope.core.layout.slotsForRow
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
fun ConfigScreen(viewModel: DashboardViewModel, modifier: Modifier = Modifier, onShareLogs: (() -> Unit)? = null) {
    val fields by viewModel.fields.collectAsState()
    var filter by remember { mutableStateOf("") }
    var editingField by remember { mutableStateOf<FieldDefinition?>(null) }
    var debugLogOpen by remember { mutableStateOf(false) }
    var showNoDataFields by remember { mutableStateOf(false) }
    val vehicleProtocol by viewModel.vehicleProtocol.collectAsState()
    val autoLayout by viewModel.autoLayout.collectAsState()
    val settingsExpanded by viewModel.settingsExpanded.collectAsState()
    var protocolMenuOpen by remember { mutableStateOf(false) }
    var pendingProtocol by remember { mutableStateOf<Elm327Protocol?>(null) }
    val busFields = fields.filter { it.busProtocol == vehicleProtocol }
    val filtered = (if (filter.isBlank()) busFields else busFields.filter { it.label.contains(filter, ignoreCase = true) })
        // Selected fields always stay listed, even if a field test once marked them "no data".
        .filter { it.enabled || showNoDataFields || !it.verifiedNoData }
    val visible = filtered.sortedByDescending { it.enabled }
    val debugLogsEnabled by viewModel.debugLogsEnabled.collectAsState()
    val testDataEnabled by viewModel.testDataEnabled.collectAsState()
    val fieldProbeStatus by viewModel.fieldProbeStatus.collectAsState()
    val noDataCount = busFields.count { it.verifiedNoData && !it.enabled }
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
        // Rarely changed settings live in a collapsible section so the field list gets most of the screen.
        val settings: @Composable () -> Unit = {
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
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Android Auto rows", style = androidx.compose.material3.MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            TextButton(
                onClick = { viewModel.setAutoLayout(autoLayout.copy(rows = autoLayout.rows.dropLast(1))) },
                enabled = autoLayout.rows.isNotEmpty(),
            ) { Text("\u2212") }
            Text("${autoLayout.rows.size}")
            TextButton(
                onClick = { viewModel.setAutoLayout(autoLayout.copy(rows = autoLayout.rows + AutoRow(TileSize.LARGE, 5))) },
                enabled = autoLayout.rows.size < MAX_AUTO_ROWS,
            ) { Text("+") }
        }
        if ((autoLayout.rows + autoLayout.remaining).count { it.size == TileSize.EXTRA_LARGE } >= 2) {
            Text(
                "Two or more X-Large rows may need a swipe to see on smaller car screens.",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            )
        }
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
        }
        TextButton(onClick = { viewModel.setSettingsExpanded(!settingsExpanded) }) {
            Text(if (settingsExpanded) "Settings \u25BE" else "Settings \u25B8")
        }
        if (settingsExpanded) settings()
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            label = { Text("Search fields (${visible.size} listed, ${busFields.count { it.enabled }} selected)") },
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
        var dragMessage by remember { mutableStateOf<String?>(null) }
        // Dashboard fields are grouped under their Android Auto row headers; rows only apply without a search.
        val showRows = filter.isBlank()
        val entries: List<ListEntry> = if (showRows) {
            val shown = visible.filter { it.enabled }
            buildList {
                val overflow = ArrayList<Int>()
                autoLayout.rows.indices.forEach { row ->
                    add(ListEntry.Header(row))
                    val members = shown.indices.filter { autoLayout.rowOf(shown[it].autoRow) == row }.map { it to shown[it].autoSlot }
                    val (slots, extra) = autoLayout.slotsForRow(row, members)
                    slots.forEachIndexed { slot, gauge ->
                        add(if (gauge == null) ListEntry.EmptySlot(row, slot) else ListEntry.Field(shown[gauge], Placement(row, slot)))
                    }
                    overflow.addAll(extra)
                }
                add(ListEntry.Header(null))
                (shown.indices.filter { autoLayout.rowOf(shown[it].autoRow) == null } + overflow).sorted()
                    .forEach { add(ListEntry.Field(shown[it], Placement(null, null))) }
                val hidden = visible.filter { !it.enabled }
                if (hidden.isNotEmpty()) add(ListEntry.Divider)
                hidden.forEach { add(ListEntry.Field(it, null)) }
            }
        } else {
            visible.map { ListEntry.Field(it, null) }
        }
        dragMessage?.let {
            Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
        }
        LazyColumn {
            items(entries, key = { it.key }) { entry ->
                when (entry) {
                    is ListEntry.Header -> {
                        val placed = entries.filterIsInstance<ListEntry.Field>().filter { it.placement != null && it.placement.row == entry.row }
                        AutoRowHeader(
                            title = entry.row?.let { "Row ${it + 1}" } ?: "Remaining rows",
                            row = entry.row?.let { autoLayout.rows[it] } ?: autoLayout.remaining,
                            count = placed.size.takeIf { entry.row != null },
                            // Only empty slots at the right-hand end can be removed.
                            minWidth = maxOf(1, (placed.maxOfOrNull { it.placement?.slot ?: 0 } ?: -1) + 1),
                            onChange = { changed ->
                                viewModel.setAutoLayout(
                                    entry.row?.let { autoLayout.copy(rows = autoLayout.rows.toMutableList().also { list -> list[it] = changed }) }
                                        ?: autoLayout.copy(remaining = changed),
                                )
                            },
                        )
                    }
                    is ListEntry.EmptySlot -> Text(
                        "Empty slot ${entry.slot + 1} \u2014 drag a gauge here",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.height(48.dp).padding(start = 104.dp, top = 14.dp),
                    )
                    ListEntry.Divider -> Text(
                        "Not on the dashboard",
                        style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                        modifier = Modifier.height(48.dp).padding(top = 16.dp),
                    )
                    is ListEntry.Field -> {
                val field = entry.field
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
                            .pointerInput(field.id, entries, autoLayout) {
                                detectDragGestures(
                                    onDragStart = { draggingId = field.id; dragOffsetY = 0f; dragMessage = null },
                                    onDragEnd = {
                                        val shift = (dragOffsetY / rowHeightPx).let { if (it >= 0) kotlin.math.floor(it) else kotlin.math.ceil(it) }.toInt()
                                        if (shift != 0) {
                                            if (showRows && field.enabled) {
                                                dragMessage = dropIntoRow(entries, field, shift, autoLayout, viewModel)
                                            } else {
                                                viewModel.moveField(field.id, shift)
                                            }
                                        }
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
            dismissButton = onShareLogs?.let { share ->
                { TextButton(onClick = share) { Text("Share") } }
            },
        )
    }
}

private const val MAX_AUTO_ROWS = 4
private const val MAX_ROW_WIDTH = 6

private sealed class ListEntry(val key: String) {
    /** Android Auto row header; [row] null = the remaining rows. */
    class Header(val row: Int?) : ListEntry("row:$row")
    class EmptySlot(val row: Int, val slot: Int) : ListEntry("slot:$row:$slot")
    object Divider : ListEntry("divider")
    /** [placement] is null for fields not on the dashboard, or when rows aren't shown. */
    class Field(val field: FieldDefinition, val placement: Placement?) : ListEntry(field.id)
}

/**
 * Drops a dashboard field [shift] places away in the grouped list: onto an empty slot it takes that
 * slot, onto a gauge in a configured row the two swap places, onto a row header it takes that
 * row's first free slot, and in the remaining rows it just moves. Returns an explanation if the
 * drop isn't possible.
 */
private fun dropIntoRow(
    entries: List<ListEntry>,
    field: FieldDefinition,
    shift: Int,
    layout: AutoLayoutConfig,
    viewModel: DashboardViewModel,
): String? {
    val from = entries.indexOfFirst { it is ListEntry.Field && it.field.id == field.id }
    if (from < 0) return null
    val boundary = entries.indexOfFirst { it is ListEntry.Divider }.let { if (it < 0) entries.size else it }
    val targetIndex = (from + shift).coerceIn(0, boundary - 1)
    val dashboard = entries.take(boundary).filterIsInstance<ListEntry.Field>()
    val placements = dashboard.associate { it.field.id to (it.placement ?: Placement(null, null)) }.toMutableMap()
    val own = placements.getValue(field.id)
    when (val target = entries[targetIndex]) {
        is ListEntry.EmptySlot -> placements[field.id] = Placement(target.row, target.slot)
        is ListEntry.Field -> {
            val theirs = placements.getValue(target.field.id)
            if (theirs.row != null) {
                placements[field.id] = theirs
                placements[target.field.id] = own
            } else {
                placements[field.id] = Placement(null, null)
            }
        }
        is ListEntry.Header -> {
            val row = target.row
            if (row == null) {
                placements[field.id] = Placement(null, null)
            } else if (own.row != row) {
                val taken = placements.filterKeys { it != field.id }.values.filter { it.row == row }.mapNotNull { it.slot }.toSet()
                val free = middleOutOrder(layout.rows[row].width).firstOrNull { it !in taken }
                    ?: return "Row ${row + 1} is full (${layout.rows[row].width} gauges) \u2014 widen it or move a gauge out first"
                placements[field.id] = Placement(row, free)
            }
        }
        ListEntry.Divider -> Unit
    }
    // Order matters for the remaining rows (and the phone dashboard): put the field where it was dropped.
    val order = dashboard.map { it.field.id }.filter { it != field.id }.toMutableList()
    val insertAt = entries.take(targetIndex + if (shift > 0) 1 else 0).count { it is ListEntry.Field && it.field.id != field.id && it.field.id in placements }
    order.add(insertAt.coerceIn(0, order.size), field.id)
    viewModel.arrangeDashboard(order, placements)
    return null
}

/** Header for one Android Auto row: tile size and how many gauges fit across it. */
@Composable
private fun AutoRowHeader(title: String, row: AutoRow, count: Int?, minWidth: Int, onChange: (AutoRow) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(48.dp).padding(top = 8.dp),
    ) {
        Text(title, style = androidx.compose.material3.MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = {
            onChange(row.copy(size = if (row.size == TileSize.EXTRA_LARGE) TileSize.LARGE else TileSize.EXTRA_LARGE))
        }) {
            Text(if (row.size == TileSize.EXTRA_LARGE) "X-Large \u21C4" else "Large \u21C4")
        }
        Text("Width", style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
        TextButton(
            onClick = { onChange(row.copy(width = row.width - 1)) },
            enabled = row.width > minWidth,
        ) { Text("\u2212") }
        Text(count?.let { "$it/${row.width}" } ?: "${row.width}")
        TextButton(
            onClick = { onChange(row.copy(width = row.width + 1)) },
            enabled = row.width < MAX_ROW_WIDTH,
        ) { Text("+") }
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
