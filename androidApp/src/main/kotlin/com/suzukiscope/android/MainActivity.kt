package com.suzukiscope.android

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.suzukiscope.android.transport.BluetoothSppTransport
import com.suzukiscope.android.transport.listBondedDevices
import com.suzukiscope.ui.AppScreen
import com.suzukiscope.ui.ConnectionBar
import com.suzukiscope.ui.SuzukiScopeTheme
import kotlinx.coroutines.launch

private const val CRITICAL_FLASH_MS = 2500L

/**
 * Uses the shared `ui` module's DashboardScreen/DashboardViewModel, backed by the
 * app-process-wide AppState so state (connection, live values, logging, DTCs) is identical
 * whether driven from this screen or the Android Auto session.
 */
class MainActivity : ComponentActivity() {

    private val requestBluetoothPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> pendingBluetoothConnect?.let { if (granted) it() }; pendingBluetoothConnect = null }
    private var pendingBluetoothConnect: (() -> Unit)? = null

    private val requestWifiSsidPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* best-effort: SSID just won't show if refused, connecting still works */ }

    // Devices offered in the "choose Bluetooth device" dialog; empty means the dialog is hidden.
    private var bluetoothDeviceChoices by mutableStateOf<List<BluetoothDevice>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppState.dashboardViewModel.setExportSink { files -> ExportStore.save(this, files) }
        setContent {
            val viewModel = AppState.dashboardViewModel
            val dtcViewModel = AppState.dtcViewModel
            val connectionStatus by AppState.connectionStatus.collectAsState()
            val wifiConfig by AppState.wifiConfig.collectAsState()
            val testDataEnabled by viewModel.testDataEnabled.collectAsState()
            val reconnectPrompt by AppState.reconnectPrompt.collectAsState()
            var connectionLogsOpen by remember { mutableStateOf(false) }
            var criticalFlashUntil by remember { mutableStateOf(0L) }
            LaunchedEffect(Unit) {
                viewModel.criticalEvents.collect { criticalFlashUntil = System.currentTimeMillis() + CRITICAL_FLASH_MS }
            }
            var flashing by remember { mutableStateOf(false) }
            LaunchedEffect(criticalFlashUntil) {
                if (criticalFlashUntil == 0L) return@LaunchedEffect
                flashing = true
                kotlinx.coroutines.delay(CRITICAL_FLASH_MS)
                flashing = false
            }
            val flashColor by animateColorAsState(
                if (flashing) androidx.compose.ui.graphics.Color.Red.copy(alpha = 0.35f) else androidx.compose.ui.graphics.Color.Transparent,
                animationSpec = tween(300),
                label = "criticalFlash",
            )

            SuzukiScopeTheme {
                Surface(modifier = androidx.compose.ui.Modifier.safeDrawingPadding()) {
                    AppScreen(
                        dashboardViewModel = viewModel,
                        dtcViewModel = dtcViewModel,
                        onExportCsv = { files -> exportFiles(files) },
                        connectionBar = {
                            ConnectionBar(
                                status = connectionStatus,
                                onToggleConnection = { lifecycleScope.launch { AppState.toggleConnection() } },
                                onViewLogs = { connectionLogsOpen = true },
                                onSelectWifi = {
                                    AppState.selectWifi()
                                    if (!AppState.hasWifiSsidPermission()) {
                                        requestWifiSsidPermission.launch(AppState.wifiSsidPermission())
                                    }
                                },
                                onSelectBluetooth = { connectToBluetooth() },
                                onUseSimulated = { AppState.useSimulated() },
                                showTestData = testDataEnabled,
                                onOpenWifiSettings = { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
                                onOpenBluetoothSettings = { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                                wifiHost = wifiConfig.host,
                                wifiPort = wifiConfig.port,
                                wifiLockedSsid = wifiConfig.lockedSsid,
                                onSaveWifiConfig = { host, port, lockedSsid -> AppState.setWifiConfig(host, port, lockedSsid) },
                            )
                        },
                    )
                }
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxSize().background(flashColor),
                )
                if (reconnectPrompt != null) {
                    AlertDialog(
                        onDismissRequest = { AppState.dismissReconnectPrompt() },
                        title = { Text("Reconnect") },
                        text = { Text(reconnectPrompt.orEmpty()) },
                        confirmButton = {
                            TextButton(onClick = {
                                AppState.dismissReconnectPrompt()
                                lifecycleScope.launch { AppState.autoReconnectFromLastSession() }
                            }) { Text("Retry") }
                        },
                        dismissButton = {
                            androidx.compose.foundation.layout.Row {
                                TextButton(onClick = {
                                    AppState.dismissReconnectPrompt()
                                    startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                                }) { Text("Choose Wi-Fi") }
                                TextButton(onClick = {
                                    AppState.dismissReconnectPrompt()
                                    AppState.useSimulated()
                                }) { Text("Use test data") }
                            }
                        },
                    )
                }
                if (connectionLogsOpen) {
                    AlertDialog(
                        onDismissRequest = { connectionLogsOpen = false },
                        title = { Text("Connection logs") },
                        text = {
                            Text(
                                viewModel.connectionLogsForExport().joinToString("\n\n") { log ->
                                    "Connection ${log.id}\n${log.contents}"
                                }.ifEmpty { "No connection logs recorded." },
                                modifier = androidx.compose.ui.Modifier.verticalScroll(rememberScrollState()),
                            )
                        },
                        confirmButton = { TextButton(onClick = { connectionLogsOpen = false }) { Text("Close") } },
                    )
                }
                if (bluetoothDeviceChoices.isNotEmpty()) {
                    AlertDialog(
                        onDismissRequest = { bluetoothDeviceChoices = emptyList() },
                        confirmButton = {
                            TextButton(onClick = { bluetoothDeviceChoices = emptyList() }) { Text("Cancel") }
                        },
                        title = { Text("Choose Bluetooth adapter") },
                        text = {
                            androidx.compose.foundation.layout.Column {
                                bluetoothDeviceChoices.forEach { device ->
                                    ListItem(
                                        headlineContent = { Text(device.name ?: device.address) },
                                        supportingContent = { Text(device.address) },
                                        modifier = androidx.compose.ui.Modifier.clickable {
                                            bluetoothDeviceChoices = emptyList()
                                            AppState.selectBluetooth(BluetoothSppTransport(device))
                                        },
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    private fun connectToBluetooth() {
        val doConnect: () -> Unit = {
            val devices = listBondedDevices(this)
            when {
                devices.isEmpty() -> AppState.setStatus(
                    "No paired Bluetooth devices found — pair your ELM327 adapter in Android Bluetooth settings first",
                )
                devices.size == 1 -> AppState.selectBluetooth(BluetoothSppTransport(devices.first()))
                else -> bluetoothDeviceChoices = devices
            }
        }

        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pendingBluetoothConnect = doConnect
            requestBluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            doConnect()
        }
    }

    private fun exportFiles(files: List<com.suzukiscope.ui.DashboardViewModel.ExportFile>) {
        startActivity(ExportStore.openFolderIntent(this))
    }
}
