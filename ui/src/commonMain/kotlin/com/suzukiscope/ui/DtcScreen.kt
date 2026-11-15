package com.suzukiscope.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import com.suzukiscope.core.dtc.DtcCode
import com.suzukiscope.core.dtc.DtcSession
import com.suzukiscope.core.dtc.describeDtcStatus
import com.suzukiscope.core.ecu.EcuTables
import kotlinx.coroutines.launch

/**
 * Fault codes: the engine ECU (read every way it might answer, with a confirmed clear), the stored
 * fault history from the last ECU snapshot, and the other modules found in the field catalogue.
 */
@Composable
fun DtcScreen(
    viewModel: DtcViewModel,
    moduleOptions: List<ModuleOption>,
    modifier: Modifier = Modifier,
    faultHistory: List<EcuTables.FaultEntry> = emptyList(),
) {
    val scope = rememberCoroutineScope()
    val busy by viewModel.busy.collectAsState()
    val engineResults by viewModel.engineResults.collectAsState()
    val engineStatus by viewModel.engineStatus.collectAsState()
    val codes by viewModel.codes.collectAsState()
    val status by viewModel.status.collectAsState()
    val unresponsiveModules by viewModel.unresponsiveModules.collectAsState()
    val probeStatus by viewModel.probeStatus.collectAsState()
    var engineExpanded by remember { mutableStateOf(true) }
    var historyExpanded by remember { mutableStateOf(false) }
    var modulesExpanded by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var showUnresponsive by remember { mutableStateOf(false) }
    // The engine has its own card; on CAN the catalogue's engine entry is the same ECU.
    val otherModules = moduleOptions.filterNot { it.isCan && it.targetAddress == DtcSession.ENGINE_ECU }
    val answering = otherModules.filter { DtcViewModel.moduleKey(it) !in unresponsiveModules }
    val visibleModules = if (showUnresponsive) otherModules else answering
    var selectedIndex by remember { mutableStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    val selected = visibleModules.getOrNull(selectedIndex)

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear engine codes?") },
            text = {
                Text(
                    "An ECU snapshot is saved before and after clearing. Clearing also erases freeze-frame data and " +
                        "resets the readiness monitors, which then need a full drive cycle before an emissions or " +
                        "roadworthy test will pass. Codes for faults that are still present will come back.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; scope.launch { viewModel.clearEngine() } }) { Text("Clear codes") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }

    Column(modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
        val engineCodeCount = engineResults?.sumOf { it.codes.size }
        SectionCard(
            icon = Icons.Outlined.Warning,
            title = "Engine",
            summary = if (engineCodeCount == null) engineStatus else "$engineCodeCount code(s) \u00B7 $engineStatus",
            expanded = engineExpanded,
            onToggle = { engineExpanded = !engineExpanded },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = { scope.launch { viewModel.readEngine() } }, enabled = !busy) { Text("Read codes") }
                OutlinedButton(
                    onClick = { confirmClear = true },
                    enabled = !busy && engineResults != null,
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("Clear codes") }
            }
            Text(engineStatus, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            engineResults?.forEach { result ->
                SubHeading(result.source.label + if (result.error == null) " (${result.codes.size})" else "")
                val error = result.error
                when {
                    error != null -> Text(error.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall)
                    result.codes.isEmpty() -> Text("None", style = MaterialTheme.typography.bodySmall)
                    else -> result.codes.forEach { CodeRow(it) }
                }
            }
        }

        if (faultHistory.isNotEmpty()) {
            SectionCard(
                icon = Icons.Outlined.History,
                title = "Fault history",
                summary = "${faultHistory.size} entr${if (faultHistory.size == 1) "y" else "ies"} from the last ECU snapshot",
                expanded = historyExpanded,
                onToggle = { historyExpanded = !historyExpanded },
            ) {
                Text(
                    "Faults the engine ECU recorded, with the odometer reading at the time.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
                faultHistory.forEach { entry ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(entry.code, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(
                            (entry.odometerKm?.let { "at %,d km".format(it) } ?: "odometer unknown") + "  \u00B7 table ${entry.source}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        SectionCard(
            icon = Icons.Outlined.Memory,
            title = "Other modules",
            summary = "${answering.size} of ${otherModules.size} answering",
            expanded = modulesExpanded,
            onToggle = { modulesExpanded = !modulesExpanded },
        ) {
            ActionRow("Try every module to find which ones answer on this car", "Scan modules", enabled = !busy) {
                scope.launch { viewModel.probeModules(otherModules) }
            }
            probeStatus?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            ToggleRow("Show ${otherModules.size - answering.size} module(s) that didn't answer", showUnresponsive) {
                showUnresponsive = it
                selectedIndex = 0
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                OutlinedButton(onClick = { menuOpen = true }, enabled = visibleModules.isNotEmpty(), modifier = Modifier.weight(1f)) {
                    Text(selected?.label ?: "No answering modules \u2014 scan first")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                        visibleModules.forEachIndexed { index, option ->
                            DropdownMenuItem(text = { Text(option.label) }, onClick = { selectedIndex = index; menuOpen = false })
                        }
                    }
                }
                Button(
                    onClick = { selected?.let { scope.launch { viewModel.readCodes(it.targetAddress, it.isFunctionalAddress, it.isCan) } } },
                    enabled = !busy && selected != null,
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("Read") }
            }
            Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
            codes.forEach { CodeRow(it) }
        }
    }
}

@Composable
private fun CodeRow(code: DtcCode) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(code.code, style = MaterialTheme.typography.titleSmall)
        Text(code.description ?: "No description available", style = MaterialTheme.typography.bodySmall)
        code.statusByte?.let { Text(describeDtcStatus(it), style = MaterialTheme.typography.labelSmall) }
    }
}
