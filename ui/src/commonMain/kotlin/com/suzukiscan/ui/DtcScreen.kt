package com.suzukiscan.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
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
import kotlinx.coroutines.launch

/**
 * Reads DTCs on a chosen module, mirroring SZ Viewer's per-module "read codes" action.
 * [moduleOptions] is derived from whichever fields the user has loaded (distinct module
 * addresses seen in the field catalog), and carries whether each module is CAN-UDS or
 * KWP2000 K-Line so the response is parsed with the correct byte framing.
 */
@Composable
fun DtcScreen(
    viewModel: DtcViewModel,
    moduleOptions: List<ModuleOption>,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val codes by viewModel.codes.collectAsState()
    val status by viewModel.status.collectAsState()
    var selectedIndex by remember { mutableStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    val selected = moduleOptions.getOrNull(selectedIndex)

    Column(modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { menuOpen = true }, modifier = Modifier.weight(1f)) {
                Text(selected?.label ?: "No modules available")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    moduleOptions.forEachIndexed { index, option ->
                        DropdownMenuItem(text = { Text(option.label) }, onClick = { selectedIndex = index; menuOpen = false })
                    }
                }
            }
            IconButton(onClick = {
                selected?.let { option ->
                    scope.launch { viewModel.readCodes(option.targetAddress, option.isFunctionalAddress, option.isCan) }
                }
            }) {
                Text("\uD83D\uDD0D") // magnifying-glass "scan" icon
            }
        }
        Text(status, modifier = Modifier.padding(vertical = 8.dp))
        LazyColumn {
            items(codes) { code ->
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Text("${code.code}" + (code.statusByte?.let { " (status 0x%02X)".format(it) } ?: ""))
                    Text(code.description ?: "No description available", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
