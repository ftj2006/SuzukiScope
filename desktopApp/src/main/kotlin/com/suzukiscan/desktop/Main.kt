package com.suzukiscan.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.suzukiscan.core.session.Elm327LiveDataSource
import com.suzukiscan.core.session.SimulatedLiveDataSource
import com.suzukiscan.core.transport.WifiTransport
import com.suzukiscan.desktop.transport.SerialTransport
import com.suzukiscan.ui.AppScreen
import com.suzukiscan.ui.ConnectionBar
import com.suzukiscan.ui.DashboardViewModel
import com.suzukiscan.ui.DtcViewModel
import com.suzukiscan.ui.SuzukiScanTheme
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Suzuki Scan (Windows)") {
        val scope = rememberCoroutineScope()
        val viewModel = remember { DashboardViewModel(scope, SimulatedLiveDataSource()) }
        val dtcViewModel = remember { DtcViewModel() }
        var connectionStatus by remember { mutableStateOf("Simulated data") }
        LaunchedEffect(Unit) { viewModel.start() }

        SuzukiScanTheme {
            AppScreen(
                dashboardViewModel = viewModel,
                dtcViewModel = dtcViewModel,
                onExportCsv = { fileName, csv -> saveCsvViaFileDialog(fileName, csv) },
                onExportLog = { fileName, log -> saveLogViaFileDialog(fileName, log) },
                connectionBar = {
                    ConnectionBar(
                        status = connectionStatus,
                        onConnectWifi = {
                            scope.launch {
                                connectionStatus = "Connecting to ELM327 Wi-Fi adapter..."
                                try {
                                    val transport = WifiTransport()
                                    val source = Elm327LiveDataSource(transport)
                                    source.connect()
                                    viewModel.useSource(source)
                                    dtcViewModel.attachClient(source.client)
                                    connectionStatus = "Connected: ${transport.name}"
                                } catch (e: Exception) {
                                    connectionStatus = "Wi-Fi connection failed: ${e.message}"
                                }
                            }
                        },
                        onConnectBluetooth = {
                            scope.launch {
                                // On Windows, a paired Bluetooth SPP adapter shows up as a COM port —
                                // there's no separate "Bluetooth" API to call, just the right serial port.
                                val port = SerialTransport.listPorts().firstOrNull()
                                if (port == null) {
                                    connectionStatus = "No serial/Bluetooth COM ports found"
                                    return@launch
                                }
                                connectionStatus = "Connecting to Bluetooth adapter on $port..."
                                try {
                                    val transport = SerialTransport(port)
                                    val source = Elm327LiveDataSource(transport)
                                    source.connect()
                                    viewModel.useSource(source)
                                    dtcViewModel.attachClient(source.client)
                                    connectionStatus = "Connected: ${transport.name}"
                                } catch (e: Exception) {
                                    connectionStatus = "Bluetooth connection failed: ${e.message}"
                                }
                            }
                        },
                        onUseSimulated = {
                            viewModel.useSource(SimulatedLiveDataSource())
                            connectionStatus = "Simulated data"
                        },
                    )
                },
            )
        }
    }
}

private fun saveCsvViaFileDialog(fileName: String, csv: String) {
    val dialog = FileDialog(null as Frame?, "Export live-data log", FileDialog.SAVE)
    dialog.file = fileName
    dialog.isVisible = true
    val name = dialog.file ?: return
    val dir = dialog.directory ?: return
    File(dir, name).writeText(csv)
}

private fun saveLogViaFileDialog(fileName: String, log: String) {
    val dialog = FileDialog(null as Frame?, "Export connection log", FileDialog.SAVE)
    dialog.file = fileName
    dialog.isVisible = true
    val name = dialog.file ?: return
    val dir = dialog.directory ?: return
    File(dir, name).writeText(log)
}
