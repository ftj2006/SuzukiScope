package com.suzukiscan.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
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
import com.suzukiscan.core.transport.WifiTransport

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
    onConnectWifi: () -> Unit,
    onConnectBluetooth: () -> Unit,
    onUseSimulated: () -> Unit = {},
    onOpenWifiSettings: (() -> Unit)? = null,
    onOpenBluetoothSettings: (() -> Unit)? = null,
    wifiHost: String = WifiTransport.DEFAULT_HOST,
    wifiPort: Int = WifiTransport.DEFAULT_PORT,
    onSaveWifiConfig: ((host: String, port: Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var wifiConfigOpen by remember { mutableStateOf(false) }
    val isBusy = status.contains("Connecting", ignoreCase = true)

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(contentAlignment = Alignment.Center) {
            FilledIconButton(onClick = { menuOpen = true }) {
                Text("\uD83D\uDD0C") // plug icon
            }
            if (isBusy) {
                CircularProgressIndicator(modifier = Modifier.size(40.dp), strokeWidth = 2.dp)
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text("Connect Wi-Fi ELM327") }, onClick = { menuOpen = false; onConnectWifi() })
            DropdownMenuItem(text = { Text("Connect Bluetooth ELM327") }, onClick = { menuOpen = false; onConnectBluetooth() })
            DropdownMenuItem(text = { Text("Use simulated data") }, onClick = { menuOpen = false; onUseSimulated() })
            if (onSaveWifiConfig != null || onOpenWifiSettings != null || onOpenBluetoothSettings != null) {
                HorizontalDivider()
            }
            if (onSaveWifiConfig != null) {
                DropdownMenuItem(
                    text = { Text("Wi-Fi adapter address (IP/port)\u2026") },
                    onClick = { menuOpen = false; wifiConfigOpen = true },
                )
            }
            if (onOpenWifiSettings != null) {
                DropdownMenuItem(text = { Text("Wi-Fi network settings\u2026") }, onClick = { menuOpen = false; onOpenWifiSettings() })
            }
            if (onOpenBluetoothSettings != null) {
                DropdownMenuItem(text = { Text("Bluetooth settings\u2026") }, onClick = { menuOpen = false; onOpenBluetoothSettings() })
            }
        }
        Text(status, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
    }

    if (wifiConfigOpen && onSaveWifiConfig != null) {
        var host by remember(wifiConfigOpen) { mutableStateOf(wifiHost) }
        var port by remember(wifiConfigOpen) { mutableStateOf(wifiPort.toString()) }
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
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onSaveWifiConfig(host, port.toIntOrNull() ?: WifiTransport.DEFAULT_PORT)
                    wifiConfigOpen = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { wifiConfigOpen = false }) { Text("Cancel") } },
        )
    }
}
