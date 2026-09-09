package com.suzukiscope.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.suzukiscope.core.transport.WifiTransport

/**
 * Connection control used by androidApp: a single icon opens a dropdown to pick
 * Wi-Fi/Bluetooth/Simulated instead of one button per option, to keep the header compact.
 * Wi-Fi is a plain TCP socket everywhere; Bluetooth needs platform-specific device APIs, each
 * platform supplies its own callback. Also offers shortcuts into the OS Wi-Fi/Bluetooth
 * settings so the user can pick/pair a specific network or device, and an in-app dialog to
 * edit the ELM327 adapter's IP/port (defaults match the standard ELM327 Wi-Fi adapter).
 */
@Composable
fun ConnectionBar(
    status: String,
    onToggleConnection: () -> Unit,
    onViewLogs: () -> Unit = {},
    onSelectWifi: () -> Unit,
    onSelectBluetooth: () -> Unit,
    onUseSimulated: () -> Unit = {},
    showTestData: Boolean = true,
    onOpenWifiSettings: (() -> Unit)? = null,
    onOpenBluetoothSettings: (() -> Unit)? = null,
    wifiHost: String = WifiTransport.DEFAULT_HOST,
    wifiPort: Int = WifiTransport.DEFAULT_PORT,
    wifiLockedSsid: String? = null,
    onSaveWifiConfig: ((host: String, port: Int, lockedSsid: String?) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var wifiConfigOpen by remember { mutableStateOf(false) }
    val isBusy = status.contains("Connecting", ignoreCase = true) || status.contains("initialising", ignoreCase = true)
    val isConnected = status.startsWith("Connected:") || status.contains("live data", ignoreCase = true)
    val hasError = listOf("error", "failed", "lost", "unreachable", "timed out").any { status.contains(it, ignoreCase = true) }

    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                FilledIconButton(onClick = onToggleConnection) {
                    Text(if (isConnected) "\u23F9" else "\uD83D\uDD0C")
                }
                if (isBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(40.dp), strokeWidth = 2.dp)
                }
            }
            IconButton(onClick = { menuOpen = true }) {
                androidx.compose.material3.Icon(Icons.Outlined.Settings, contentDescription = "Connection settings")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("WiFi") }, onClick = { menuOpen = false; onSelectWifi() })
                DropdownMenuItem(text = { Text("Bluetooth") }, onClick = { menuOpen = false; onSelectBluetooth() })
                if (showTestData) {
                    DropdownMenuItem(text = { Text("Test Data") }, onClick = { menuOpen = false; onUseSimulated() })
                }
                if (onSaveWifiConfig != null || onOpenWifiSettings != null || onOpenBluetoothSettings != null) {
                    HorizontalDivider()
                }
                if (onSaveWifiConfig != null) {
                    DropdownMenuItem(
                        text = { Text("Wifi settings") },
                        onClick = { menuOpen = false; wifiConfigOpen = true },
                    )
                }
                if (onOpenWifiSettings != null && onSaveWifiConfig == null) {
                    DropdownMenuItem(text = { Text("Wifi settings") }, onClick = { menuOpen = false; onOpenWifiSettings() })
                }
                if (onOpenBluetoothSettings != null) {
                    DropdownMenuItem(text = { Text("Bluetooth settings\u2026") }, onClick = { menuOpen = false; onOpenBluetoothSettings() })
                }
            }
        }
        Text(
            status,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 2.dp)
                .then(if (hasError) Modifier.clickable(onClick = onViewLogs) else Modifier),
        )
    }

    if (wifiConfigOpen && onSaveWifiConfig != null) {
        var host by remember(wifiConfigOpen) { mutableStateOf(wifiHost) }
        var port by remember(wifiConfigOpen) { mutableStateOf(wifiPort.toString()) }
        var lockedSsid by remember(wifiConfigOpen) { mutableStateOf(wifiLockedSsid.orEmpty()) }
        AlertDialog(
            onDismissRequest = { wifiConfigOpen = false },
            title = { Text("Wi-Fi adapter address") },
            text = {
                Column {
                    OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("IP address") }, singleLine = true)
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter(Char::isDigit) },
                        label = { Text("Port") },
                        singleLine = true,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    OutlinedTextField(
                        value = lockedSsid,
                        onValueChange = { lockedSsid = it },
                        label = { Text("Lock to Wi-Fi network (optional)") },
                        singleLine = true,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        "When set, Connect refuses to run unless the phone is already joined to this network.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (onOpenWifiSettings != null) {
                        TextButton(onClick = onOpenWifiSettings) { Text("Choose WiFi network") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onSaveWifiConfig(host, port.toIntOrNull() ?: WifiTransport.DEFAULT_PORT, lockedSsid.trim().ifBlank { null })
                    wifiConfigOpen = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { wifiConfigOpen = false }) { Text("Cancel") } },
        )
    }
}
