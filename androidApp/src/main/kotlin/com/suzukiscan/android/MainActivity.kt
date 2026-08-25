package com.suzukiscan.android

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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.suzukiscan.android.transport.BluetoothSppTransport
import com.suzukiscan.android.transport.listBondedDevices
import com.suzukiscan.ui.AppScreen
import com.suzukiscan.ui.ConnectionBar
import com.suzukiscan.ui.SuzukiScanTheme
import kotlinx.coroutines.launch
import java.io.File

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

    // Devices offered in the "choose Bluetooth device" dialog; empty means the dialog is hidden.
    private var bluetoothDeviceChoices by mutableStateOf<List<BluetoothDevice>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppState.initPersistence(File(filesDir, "field-config.json"))
        AppState.initWifiConfig(File(filesDir, "wifi-config.json"))
        AppState.initAlerts(applicationContext)
        setContent {
            val viewModel = AppState.dashboardViewModel
            val dtcViewModel = AppState.dtcViewModel
            val connectionStatus by AppState.connectionStatus.collectAsState()
            val wifiConfig by AppState.wifiConfig.collectAsState()

            SuzukiScanTheme {
                Surface(modifier = androidx.compose.ui.Modifier.safeDrawingPadding()) {
                    AppScreen(
                        dashboardViewModel = viewModel,
                        dtcViewModel = dtcViewModel,
                        onExportCsv = { fileName, csv -> shareCsv(fileName, csv) },
                        onExportLog = { fileName, log -> shareLog(fileName, log) },
                        connectionBar = {
                            ConnectionBar(
                                status = connectionStatus,
                                onConnectWifi = { lifecycleScope.launch { AppState.connectWifi() } },
                                onConnectBluetooth = { connectToBluetooth() },
                                onUseSimulated = { AppState.useSimulated() },
                                onOpenWifiSettings = { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
                                onOpenBluetoothSettings = { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                                wifiHost = wifiConfig.host,
                                wifiPort = wifiConfig.port,
                                onSaveWifiConfig = { host, port -> AppState.setWifiConfig(host, port) },
                            )
                        },
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
                                            lifecycleScope.launch { AppState.connectBluetooth(BluetoothSppTransport(device)) }
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
                devices.size == 1 -> lifecycleScope.launch { AppState.connectBluetooth(BluetoothSppTransport(devices.first())) }
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

    private fun shareCsv(fileName: String, csv: String) {
        val file = File(cacheDir, fileName).apply { writeText(csv) }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Export live-data log"))
    }

    private fun shareLog(fileName: String, log: String) {
        val file = File(cacheDir, fileName).apply { writeText(log) }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Export connection log"))
    }
}
