package com.suzukiscan.android

import com.suzukiscan.core.field.FieldRegistryStore
import com.suzukiscan.core.elm327.Elm327Protocol
import com.suzukiscan.core.log.describeError
import com.suzukiscan.core.session.Elm327LiveDataSource
import com.suzukiscan.core.session.SimulatedLiveDataSource
import com.suzukiscan.core.transport.Transport
import com.suzukiscan.core.transport.WifiConfig
import com.suzukiscan.core.transport.WifiTransport
import com.suzukiscan.ui.DashboardViewModel
import com.suzukiscan.ui.DtcViewModel
import com.suzukiscan.android.transport.BluetoothSppTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import android.net.wifi.WifiManager
import android.os.Build

/**
 * App-process-wide connection/dashboard state, shared between the phone Activity and the
 * Android Auto CarAppService/Session (both run in the same app process for phone-projected
 * Auto) — so "Connect", live values, logging, and DTCs are the same whether driven from the
 * phone screen or the head unit.
 */
object AppState {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var appContext: android.content.Context? = null
    private var lastWifiSsid: String? = null
    private var lastWarnedWrongSsid: String? = null
    private var persistFile: File? = null
    private var wifiConfigFile: File? = null
    private var lastConnectionFile: File? = null
    private var dtcModulesFile: File? = null
    private var autoProbeFile: File? = null

    private val _reconnectPrompt = MutableStateFlow<String?>(null)
    /** Set when auto-reconnecting to the last-used connection on startup fails, so the UI can
     * offer to retry or pick a different network instead of silently sitting on test data. */
    val reconnectPrompt: StateFlow<String?> = _reconnectPrompt.asStateFlow()
    fun dismissReconnectPrompt() { _reconnectPrompt.value = null }

    private val _wifiConfig = MutableStateFlow(WifiConfig())
    val wifiConfig: StateFlow<WifiConfig> = _wifiConfig.asStateFlow()

    val dashboardViewModel: DashboardViewModel = DashboardViewModel(
        scope,
        SimulatedLiveDataSource(),
        persist = { persistFile?.let { FieldRegistryStore.save(dashboardViewModel.registry, it) } },
    )
    val dtcViewModel = DtcViewModel(
        persistUnresponsiveModules = { keys -> dtcModulesFile?.let { runCatching { it.parentFile?.mkdirs(); it.writeText(keys.joinToString(",")) } } },
    )

    private val _connectionStatus = MutableStateFlow("Simulated data")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    fun setStatus(status: String) {
        _connectionStatus.value = status
    }

    /** Call once after the init* functions, from wherever first starts the process (Application,
     * not just MainActivity, since Android Auto can launch this process on its own). */
    fun startup() {
        scope.launch { autoReconnectFromLastSession() }
    }

    /** Wires up vibration/tone alerts on warning-threshold crossings; call once with an Application context. */
    fun initAlerts(context: android.content.Context) {
        appContext = context.applicationContext
        dashboardViewModel.alertSink = AndroidCriticalAlertSink(context)
        AutoCrashLog.init(context)
        AutoCrashLog.setEnabled(dashboardViewModel.debugLogsEnabled.value)
        dashboardViewModel.debugLogsSettingSink = AutoCrashLog::setEnabled
    }

    /** Loads previously-saved field config (order/enabled/gauge max/thresholds) from disk, if any. */
    fun initPersistence(file: File) {
        if (persistFile != null) return
        persistFile = file
        FieldRegistryStore.load(dashboardViewModel.registry, file)
        dashboardViewModel.refreshFields()
    }

    /** Loads the previously-saved Wi-Fi adapter IP/port, if any (defaults to the standard ELM327 adapter). */
    fun initWifiConfig(file: File) {
        if (wifiConfigFile != null) return
        wifiConfigFile = file
        _wifiConfig.value = WifiConfig.load(file)
    }

    /** Loads previously-persisted unresponsive DTC module keys, if any. */
    fun initDtcModulesPersistence(file: File) {
        if (dtcModulesFile != null) return
        dtcModulesFile = file
        if (file.exists()) {
            val keys = file.readText().split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
            dtcViewModel.loadUnresponsiveModules(keys)
        }
    }

    /** Enables the one-time "which fields/modules actually respond on this vehicle" scan; see
     * [maybeRunFirstConnectionProbe]. */
    fun initAutoProbe(file: File) {
        if (autoProbeFile != null) return
        autoProbeFile = file
    }

    /** Enables remembering/restoring the last-used connection method; see [autoReconnectFromLastSession]. */
    fun initLastConnection(file: File) {
        if (lastConnectionFile != null) return
        lastConnectionFile = file
    }

    private fun rememberLastConnection(kind: String) {
        val file = lastConnectionFile ?: return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(kind)
        }
    }

    /**
     * Runs once, ever, per install: the first time a real hardware connection succeeds, silently
     * scans every field and DTC module (not just the enabled/default ones) in the background to
     * find out which ones this specific vehicle actually responds to, then hides the rest by
     * default in the Config/DTC screens — since other vehicles/modules the app is theoretically
     * compatible with will differ from any one owner's. Re-run manually any time via "Test all
     * fields"/"Test modules" if you connect to a different vehicle later.
     */
    private fun maybeRunFirstConnectionProbe() {
        val file = autoProbeFile ?: return
        if (file.exists()) return
        scope.launch {
            dashboardViewModel.probeAllFieldsForData()
            dtcViewModel.probeModules(com.suzukiscan.ui.moduleOptionsFrom(dashboardViewModel.fields.value))
            runCatching { file.parentFile?.mkdirs(); file.writeText("done") }
        }
    }

    /**
     * Called once at app startup: reconnects to whatever was last used (Wi-Fi, a specific bonded
     * Bluetooth device, or test data) instead of always starting on simulated data. If the
     * remembered hardware connection can't be re-established, sets [reconnectPrompt] instead of
     * silently falling back, so the driver can choose to retry or pick a different network.
     */
    suspend fun autoReconnectFromLastSession() {
        val kind = lastConnectionFile?.takeIf { it.exists() }?.readText()?.trim() ?: return
        when {
            kind == "wifi" -> {
                selectWifi()
                if (!connectWifi()) _reconnectPrompt.value = "Could not reconnect to Wi-Fi automatically."
            }
            kind.startsWith("bluetooth:") -> {
                val address = kind.removePrefix("bluetooth:")
                val context = appContext
                val hasPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    context != null && androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
                val device = if (hasPermission) {
                    runCatching {
                        (context?.getSystemService(android.bluetooth.BluetoothManager::class.java))
                            ?.adapter?.bondedDevices?.firstOrNull { it.address == address }
                    }.getOrNull()
                } else {
                    null
                }
                if (device != null) {
                    val transport = BluetoothSppTransport(device)
                    selectBluetooth(transport)
                    if (!connectBluetooth(transport)) {
                        _reconnectPrompt.value = "Could not reconnect to Bluetooth adapter '$address' automatically."
                    }
                } else {
                    _reconnectPrompt.value = "Bluetooth adapter '$address' unavailable \u2014 reconnect manually."
                }
            }
        }
    }

    fun setWifiConfig(host: String, port: Int, lockedSsid: String? = null) {
        val config = WifiConfig(host, port, lockedSsid?.trim()?.takeUnless { it.isBlank() })
        _wifiConfig.value = config
        wifiConfigFile?.let { config.save(it) }
    }

    /** True once the OS lets us read the actually-connected Wi-Fi SSID (see [currentSsid]). */
    fun hasWifiSsidPermission(): Boolean {
        val context = appContext ?: return false
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            android.Manifest.permission.ACCESS_FINE_LOCATION
        }
        return androidx.core.content.ContextCompat.checkSelfPermission(context, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** The permission to request (via an Activity) so [currentSsid] can start working. */
    fun wifiSsidPermission(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        android.Manifest.permission.NEARBY_WIFI_DEVICES
    } else {
        android.Manifest.permission.ACCESS_FINE_LOCATION
    }

    private fun currentSsid(): String? {
        if (!hasWifiSsidPermission()) return null
        return runCatching {
            (appContext?.getSystemService(android.content.Context.WIFI_SERVICE) as? WifiManager)
                ?.connectionInfo?.ssid?.trim('"')
        }.getOrNull()?.takeUnless { it.isBlank() || it == WifiManager.UNKNOWN_SSID }
    }

    init {
        dashboardViewModel.start()
        startReconnectWatchdog()
        startWifiMonitor()
    }

    suspend fun connectWifi(): Boolean {
        val config = _wifiConfig.value
        lastWifiSsid = null
        reconnectFactory = { WifiTransport(config.host, config.port) }
        _connectionStatus.value = "Connecting to ELM327 Wi-Fi adapter (${config.host}:${config.port})..."
        return connect(WifiTransport(config.host, config.port))
    }

    suspend fun connectBluetooth(transport: Transport): Boolean {
        reconnectFactory = { transport }
        _connectionStatus.value = "Connecting to Bluetooth adapter..."
        return connect(transport)
    }

    private suspend fun connect(transport: Transport): Boolean {
        if (transport is WifiTransport) {
            val locked = _wifiConfig.value.lockedSsid
            if (!locked.isNullOrBlank()) {
                val ssid = currentSsid()
                if (ssid != null && ssid != locked) {
                    _connectionStatus.value = "Connect to Wi-Fi network '$locked' first (currently on '$ssid')"
                    return false
                }
            }
        }
        var source: Elm327LiveDataSource? = null
        try {
            _connectionStatus.value = "Preparing ${transport.name}..."
            // ELM327 must be initialised for one bus at a time — pick CAN vs KWP based on
            // whichever fields are currently enabled (responsePrefixBytes==0 means CAN-UDS).
            val enabled = dashboardViewModel.fields.value.filter { it.enabled }
            val protocol = if (enabled.isNotEmpty() && enabled.all { it.request.responsePrefixBytes == 0 }) {
                Elm327Protocol.CAN_11BIT_500K
            } else {
                Elm327Protocol.KWP_FAST
            }
            _connectionStatus.value = "Connecting to ${transport.name}..."
            source = Elm327LiveDataSource(transport, protocol)
            // Attached before connect() so a failed attempt's trace is still exportable via the
            // connection-log button, instead of only ever seeing the last successful session's.
            dashboardViewModel.attachIoLog(source.ioLog)
            source.ioLog.append(com.suzukiscan.core.log.IoDirection.RECEIVED, "Starting ELM327 initialisation using $protocol")
            _connectionStatus.value = "Connected to ${transport.name}; initialising ELM327..."
            source.connect()
            _connectionStatus.value = "ELM327 ready; starting live data..."
            dashboardViewModel.useSource(source)
            dtcViewModel.attachClient(source.client)
            _connectionStatus.value = connectionStatusFor(transport)
            rememberLastConnection(if (transport is WifiTransport) "wifi" else "bluetooth:${transport.name.removePrefix("bt://")}")
            maybeRunFirstConnectionProbe()
            return true
        } catch (e: Exception) {
            source?.ioLog?.append(
                com.suzukiscan.core.log.IoDirection.ERROR,
                "Connection failed (${e::class.simpleName}): ${describeError(e)}",
            )
            _connectionStatus.value = "Connection failed (${e::class.simpleName}): ${describeError(e)}"
            dashboardViewModel.persistCurrentConnectionLog()
            return false
        }
    }

    fun useSimulated() {
        reconnectFactory = null
        dashboardViewModel.useSource(SimulatedLiveDataSource())
        _connectionStatus.value = "Simulated data"
        rememberLastConnection("simulated")
    }

    // --- Auto-reconnect ---
    // Remembers how to re-open the last-used transport (Wi-Fi host/port, or the same Bluetooth
    // device/socket) so a dropped connection can be retried automatically instead of requiring
    // the driver to reach for the phone and tap Connect again mid-drive.
    private var reconnectFactory: (() -> Transport)? = null
    private var reconnecting = false
    private var selectedTransport: Transport? = null

    fun selectWifi() {
        dashboardViewModel.stopTestData()
        lastWifiSsid = null
        val config = _wifiConfig.value
        selectedTransport = WifiTransport(config.host, config.port)
        _connectionStatus.value = "Wi-Fi selected: ${config.host}:${config.port}"
    }

    fun selectBluetooth(transport: Transport) {
        dashboardViewModel.stopTestData()
        selectedTransport = transport
        _connectionStatus.value = "Bluetooth selected: ${transport.name}"
    }

    suspend fun toggleConnection() {
        if (dashboardViewModel.isUsingHardwareSource()) {
            reconnectFactory = null
            dashboardViewModel.disconnectHardware()
            _connectionStatus.value = "Disconnected"
            return
        }
        val transport = selectedTransport ?: run {
            selectWifi()
            selectedTransport!!
        }
        reconnectFactory = { transport }
        _connectionStatus.value = "Connecting to ${transport.name}..."
        connect(transport)
    }

    private fun startReconnectWatchdog() {
        scope.launch {
            var attempt = 0
            while (true) {
                delay(3000)
                val factory = reconnectFactory
                val stalled = dashboardViewModel.isUsingHardwareSource() &&
                    dashboardViewModel.millisSinceLastReading() > 6000
                if (factory == null || !stalled || reconnecting) continue
                reconnecting = true
                attempt++
                _connectionStatus.value = "Connection lost \u2014 reconnecting (attempt $attempt)..."
                val backoffMs = minOf(2000L * (1 shl (attempt - 1).coerceAtMost(4)), 30_000L)
                delay(backoffMs)
                val ok = connect(factory())
                if (ok) attempt = 0
                reconnecting = false
            }
        }
    }

    private fun startWifiMonitor() {
        scope.launch {
            while (true) {
                delay(2000)
                val ssid = currentSsid()
                val locked = _wifiConfig.value.lockedSsid
                val usingWifi = dashboardViewModel.isUsingHardwareSource() && dashboardViewModel.currentTransportName().startsWith("wifi://")

                if (usingWifi) {
                    if (ssid != null && ssid != lastWifiSsid) {
                        val previous = lastWifiSsid
                        lastWifiSsid = ssid
                        _connectionStatus.value = if (previous == null) {
                            "Connected: WiFi $ssid"
                        } else {
                            "WiFi network changed: $previous -> $ssid"
                        }
                        if (previous != null) notify("Wi-Fi network changed: $previous \u2192 $ssid")
                    }
                    if (ssid == null && _connectionStatus.value.startsWith("Connected: WiFi") && hasWifiSsidPermission()) {
                        _connectionStatus.value = "Connected: WiFi (SSID unavailable)"
                    }
                } else if (!locked.isNullOrBlank() && ssid != null && ssid != lastWarnedWrongSsid) {
                    // Not connected yet — let the driver know up front they're on the wrong
                    // network, since connect() will otherwise silently refuse to even try.
                    lastWarnedWrongSsid = ssid
                    if (ssid != locked) notify("On Wi-Fi '$ssid' \u2014 connect to '$locked' to use the adapter")
                }
            }
        }
    }

    /** Surfaces Wi-Fi network-change notices even if the driver isn't looking at the status line. */
    private fun notify(message: String) {
        val context = appContext ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun connectionStatusFor(transport: Transport): String {
        if (!transport.name.startsWith("wifi://")) return "Connected: ${transport.name}"
        val ssid = currentSsid()
        return if (ssid == null) "Connected: WiFi" else "Connected: WiFi $ssid"
    }
}
