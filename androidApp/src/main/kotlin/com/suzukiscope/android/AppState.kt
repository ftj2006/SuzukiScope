package com.suzukiscope.android

import com.suzukiscope.core.field.FieldRegistryStore
import com.suzukiscope.core.elm327.Elm327Protocol
import com.suzukiscope.core.log.describeError
import com.suzukiscope.core.session.Elm327LiveDataSource
import com.suzukiscope.core.session.SimulatedLiveDataSource
import com.suzukiscope.core.transport.Transport
import com.suzukiscope.core.transport.WifiConfig
import com.suzukiscope.core.transport.WifiTransport
import com.suzukiscope.ui.DashboardViewModel
import com.suzukiscope.ui.DtcViewModel
import com.suzukiscope.android.transport.BluetoothSppTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.io.File
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build

/**
 * App-process-wide connection/dashboard state, shared between the phone Activity and the
 * Android Auto CarAppService/Session (both run in the same app process for phone-projected
 * Auto) — so "Connect", live values, logging, and DTCs are the same whether driven from the
 * phone screen or the head unit.
 */
object AppState {
    // K-Line fast init plus the first shared request can take several seconds; a shorter window
    // declared fresh connections dead before their first reading and looped reconnecting.
    private const val STALL_MS = 12_000L
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

    private val _currentSsid = MutableStateFlow<String?>(null)
    val currentSsidFlow: StateFlow<String?> = _currentSsid.asStateFlow()

    private val _recordingSaved = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Location of each recording as it's saved, so the car screen can show a toast too. */
    val recordingSaved: kotlinx.coroutines.flow.SharedFlow<String> = _recordingSaved
    private val _nearbySsids = MutableStateFlow<List<String>>(emptyList())
    val nearbySsids: StateFlow<List<String>> = _nearbySsids.asStateFlow()

    // Kept up to date by [WifiCallback]; null when the phone isn't on any Wi-Fi network.
    @Volatile private var wifiNetwork: Network? = null
    @Volatile private var callbackSsid: String? = null
    // Set when a Wi-Fi connect is blocked because the locked SSID isn't the current network;
    // the watchdog connects as soon as it is.
    @Volatile private var waitingForSsid = false

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
        val appCtx = context.applicationContext
        // Set here rather than in MainActivity so recordings started from Android Auto are saved too.
        dashboardViewModel.setExportSink { files ->
            val saved = ExportStore.save(appCtx, files)
            saved.filter { it.endsWith(".csv") }.forEach {
                ExportStore.notifyRecordingSaved(appCtx, it)
                _recordingSaved.tryEmit(it)
            }
        }
        registerWifiCallback(appCtx)
    }

    private class WifiCallback : ConnectivityManager.NetworkCallback {
        constructor() : super()
        @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
        constructor(flags: Int) : super(flags)

        override fun onAvailable(network: Network) {
            wifiNetwork = network
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            wifiNetwork = network
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cleanSsid((caps.transportInfo as? WifiInfo)?.ssid)?.let { callbackSsid = it }
            }
        }

        override fun onLost(network: Network) {
            if (network == wifiNetwork) {
                wifiNetwork = null
                callbackSsid = null
            }
        }
    }

    private fun registerWifiCallback(context: android.content.Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        // Android 12+ redacts the SSID from callbacks unless explicitly asked for (still needs location permission).
        val callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            WifiCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO)
        } else {
            WifiCallback()
        }
        runCatching { cm.registerNetworkCallback(request, callback) }
    }

    private fun cleanSsid(raw: String?): String? =
        raw?.trim('"')?.takeUnless { it.isBlank() || it == WifiManager.UNKNOWN_SSID }

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
            dtcViewModel.probeModules(com.suzukiscope.ui.moduleOptionsFrom(dashboardViewModel.fields.value))
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
                if (!connectWifi() && !waitingForSsid) _reconnectPrompt.value = "Could not reconnect to Wi-Fi automatically."
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
        return androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** Permissions to request (via an Activity) so [currentSsid] can start working. Reading the
     * connected SSID needs fine location on every Android version; 12+ requires coarse alongside it. */
    fun wifiSsidPermissions(): Array<String> = arrayOf(
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    private fun isLocationEnabled(): Boolean {
        val lm = appContext?.getSystemService(android.location.LocationManager::class.java) ?: return false
        return androidx.core.location.LocationManagerCompat.isLocationEnabled(lm)
    }

    private fun currentSsid(): String? {
        if (!hasWifiSsidPermission()) return null
        callbackSsid?.let { return it }
        return runCatching {
            @Suppress("DEPRECATION")
            cleanSsid((appContext?.getSystemService(android.content.Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo?.ssid)
        }.getOrNull()
    }

    /** Why the Wi-Fi name can't be shown, or null if it can (or the phone isn't on Wi-Fi). */
    private fun ssidUnavailableReason(): String? = when {
        wifiNetwork == null -> null
        !hasWifiSsidPermission() -> "allow Location to see the network name"
        !isLocationEnabled() -> "turn on Location to see the network name"
        currentSsid() == null -> "network name unavailable"
        else -> null
    }

    /** Non-null (a status message) while a Wi-Fi connect must wait for the locked network. */
    private fun wifiBlockReason(): String? {
        val locked = _wifiConfig.value.lockedSsid ?: return null
        if (wifiNetwork == null) return "Waiting for Wi-Fi '$locked' (phone not on Wi-Fi)"
        // If the name can't be read we can't tell, so let the attempt go ahead rather than block forever.
        val ssid = currentSsid() ?: return null
        return if (ssid != locked) "Waiting for Wi-Fi '$locked' (phone on '$ssid')" else null
    }

    private fun newWifiTransport(): WifiTransport {
        val config = _wifiConfig.value
        return WifiTransport(config.host, config.port, socketFactory = wifiNetwork?.socketFactory)
    }

    private fun refreshNearbySsids() {
        if (!hasWifiSsidPermission()) return
        val wm = appContext?.getSystemService(android.content.Context.WIFI_SERVICE) as? WifiManager ?: return
        _nearbySsids.value = runCatching {
            @Suppress("DEPRECATION")
            wm.scanResults.mapNotNull { cleanSsid(it.SSID) }.distinct().sorted()
        }.getOrDefault(emptyList())
    }

    init {
        dashboardViewModel.start()
        startReconnectWatchdog()
        startWifiMonitor()
    }

    suspend fun connectWifi(): Boolean {
        val config = _wifiConfig.value
        lastWifiSsid = null
        reconnectFactory = ::newWifiTransport
        _connectionStatus.value = "Connecting to ELM327 Wi-Fi adapter (${config.host}:${config.port})..."
        return connect(newWifiTransport())
    }

    suspend fun connectBluetooth(transport: Transport): Boolean {
        reconnectFactory = { transport }
        _connectionStatus.value = "Connecting to Bluetooth adapter..."
        return connect(transport)
    }

    // Serialises connects (startup, watchdog and the Connect button can race) — ELM327 Wi-Fi
    // adapters usually accept a single client, so overlapping attempts starve each other.
    private val connectMutex = kotlinx.coroutines.sync.Mutex()

    private suspend fun connect(transport: Transport): Boolean = connectMutex.withLock { connectLocked(transport) }

    private suspend fun connectLocked(transport: Transport): Boolean {
        if (transport is WifiTransport) {
            wifiBlockReason()?.let { reason ->
                waitingForSsid = true
                _connectionStatus.value = "$reason \u2014 will connect automatically"
                return false
            }
        }
        waitingForSsid = false
        // Close any previous adapter socket first, otherwise it can hold the adapter's only client slot.
        dashboardViewModel.closeHardwareTransport()
        var source: Elm327LiveDataSource? = null
        try {
            _connectionStatus.value = "Preparing ${transport.name}..."
            // ELM327 must be initialised for one bus at a time — pick whichever most enabled fields
            // use (responsePrefixBytes==0 means CAN-UDS); fields for the other bus are skipped.
            val enabled = dashboardViewModel.fields.value.filter { it.enabled }
            val canCount = enabled.count { it.request.responsePrefixBytes == 0 }
            val protocol = if (canCount > 0 && canCount * 2 >= enabled.size) {
                Elm327Protocol.CAN_11BIT_500K
            } else {
                Elm327Protocol.KWP_FAST
            }
            _connectionStatus.value = "Connecting to ${transport.name}..."
            source = Elm327LiveDataSource(transport, protocol)
            // Attached before connect() so a failed attempt's trace is still exportable via the
            // connection-log button, instead of only ever seeing the last successful session's.
            dashboardViewModel.attachIoLog(source.ioLog)
            source.ioLog.append(com.suzukiscope.core.log.IoDirection.RECEIVED, "Starting ELM327 initialisation using $protocol")
            _connectionStatus.value = "Connected to ${transport.name}; initialising ELM327..."
            source.connect()
            _connectionStatus.value = "ELM327 ready; starting live data..."
            dashboardViewModel.useSource(source)
            dtcViewModel.attachClient(source.client)
            _connectionStatus.value = connectionStatusFor(transport)
            rememberLastConnection(if (transport is WifiTransport) "wifi" else "bluetooth:${transport.name.removePrefix("bt://")}")
            if (transport is WifiTransport) lockToCurrentSsidIfUnset()
            maybeRunFirstConnectionProbe()
            return true
        } catch (e: Exception) {
            source?.ioLog?.append(
                com.suzukiscope.core.log.IoDirection.ERROR,
                "Connection failed (${e::class.simpleName}): ${describeError(e)}",
            )
            _connectionStatus.value = "Connection failed (${e::class.simpleName}): ${describeError(e)}"
            dashboardViewModel.persistCurrentConnectionLog()
            return false
        }
    }

    fun useSimulated() {
        reconnectFactory = null
        waitingForSsid = false
        dashboardViewModel.useSource(SimulatedLiveDataSource())
        _connectionStatus.value = "Simulated data"
        rememberLastConnection("simulated")
    }

    /** First successful adapter connection remembers the network it was on, so later connects wait for it. */
    private fun lockToCurrentSsidIfUnset() {
        val config = _wifiConfig.value
        if (config.lockedSsid != null) return
        val ssid = currentSsid() ?: return
        setWifiConfig(config.host, config.port, ssid)
        notify("Locked to Wi-Fi '$ssid' \u2014 change under Wifi settings")
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
        selectedTransport = newWifiTransport()
        val config = _wifiConfig.value
        _connectionStatus.value = "Wi-Fi selected: ${config.host}:${config.port}" +
            (config.lockedSsid?.let { " on '$it'" } ?: "")
    }

    fun selectBluetooth(transport: Transport) {
        dashboardViewModel.stopTestData()
        selectedTransport = transport
        _connectionStatus.value = "Bluetooth selected: ${transport.name}"
    }

    suspend fun toggleConnection() {
        if (waitingForSsid) {
            waitingForSsid = false
            reconnectFactory = null
            _connectionStatus.value = "Disconnected"
            return
        }
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
        // Wi-Fi gets a fresh transport per attempt so it binds to whichever Wi-Fi network is current.
        val factory: () -> Transport = if (transport is WifiTransport) ::newWifiTransport else { { transport } }
        reconnectFactory = factory
        _connectionStatus.value = "Connecting to ${transport.name}..."
        connect(factory())
    }

    private fun startReconnectWatchdog() {
        scope.launch {
            var attempt = 0
            while (true) {
                delay(2000)
                val factory = reconnectFactory
                if (factory == null || reconnecting) continue
                if (waitingForSsid) {
                    val reason = wifiBlockReason()
                    if (reason != null) {
                        _connectionStatus.value = "$reason \u2014 will connect automatically"
                        continue
                    }
                    reconnecting = true
                    attempt = 0
                    _connectionStatus.value = "Wi-Fi '${_wifiConfig.value.lockedSsid}' available \u2014 connecting..."
                    connect(factory())
                    reconnecting = false
                    continue
                }
                val stalled = dashboardViewModel.isUsingHardwareSource() &&
                    dashboardViewModel.millisSinceLastReading() > STALL_MS
                if (!stalled) continue
                val onWifi = dashboardViewModel.currentTransportName().startsWith("wifi://")
                val blocked = if (onWifi) wifiBlockReason() else null
                if (blocked != null) {
                    // The adapter's network has gone; stop hammering it until the phone is back on it.
                    waitingForSsid = true
                    attempt = 0
                    _connectionStatus.value = "$blocked \u2014 will connect automatically"
                    continue
                }
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
            var tick = 0
            while (true) {
                delay(2000)
                val ssid = currentSsid()
                _currentSsid.value = ssid
                if (tick++ % 5 == 0) refreshNearbySsids()
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
                    val unavailable = ssidUnavailableReason()
                    if (ssid == null && unavailable != null && _connectionStatus.value.startsWith("Connected: WiFi")) {
                        _connectionStatus.value = "Connected: WiFi ($unavailable)"
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
        return if (ssid != null) "Connected: WiFi $ssid" else "Connected: WiFi" + (ssidUnavailableReason()?.let { " ($it)" } ?: "")
    }
}
