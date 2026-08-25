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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * App-process-wide connection/dashboard state, shared between the phone Activity and the
 * Android Auto CarAppService/Session (both run in the same app process for phone-projected
 * Auto) — so "Connect", live values, logging, and DTCs are the same whether driven from the
 * phone screen or the head unit.
 */
object AppState {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var persistFile: File? = null
    private var wifiConfigFile: File? = null

    private val _wifiConfig = MutableStateFlow(WifiConfig())
    val wifiConfig: StateFlow<WifiConfig> = _wifiConfig.asStateFlow()

    val dashboardViewModel: DashboardViewModel = DashboardViewModel(
        scope,
        SimulatedLiveDataSource(),
        persist = { persistFile?.let { FieldRegistryStore.save(dashboardViewModel.registry, it) } },
    )
    val dtcViewModel = DtcViewModel()

    private val _connectionStatus = MutableStateFlow("Simulated data")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    fun setStatus(status: String) {
        _connectionStatus.value = status
    }

    /** Wires up vibration/tone alerts on warning-threshold crossings; call once with an Application context. */
    fun initAlerts(context: android.content.Context) {
        dashboardViewModel.alertSink = AndroidWarningAlertSink(context)
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

    fun setWifiConfig(host: String, port: Int) {
        val config = WifiConfig(host, port)
        _wifiConfig.value = config
        wifiConfigFile?.let { config.save(it) }
    }

    init {
        dashboardViewModel.start()
        startReconnectWatchdog()
    }

    suspend fun connectWifi() {
        val config = _wifiConfig.value
        reconnectFactory = { WifiTransport(config.host, config.port) }
        _connectionStatus.value = "Connecting to ELM327 Wi-Fi adapter (${config.host}:${config.port})..."
        connect(WifiTransport(config.host, config.port))
    }

    suspend fun connectBluetooth(transport: Transport) {
        reconnectFactory = { transport }
        _connectionStatus.value = "Connecting to Bluetooth adapter..."
        connect(transport)
    }

    private suspend fun connect(transport: Transport): Boolean {
        try {
            // ELM327 must be initialised for one bus at a time — pick CAN vs KWP based on
            // whichever fields are currently enabled (responsePrefixBytes==0 means CAN-UDS).
            val enabled = dashboardViewModel.fields.value.filter { it.enabled }
            val protocol = if (enabled.isNotEmpty() && enabled.all { it.request.responsePrefixBytes == 0 }) {
                Elm327Protocol.CAN_11BIT_500K
            } else {
                Elm327Protocol.KWP_FAST
            }
            val source = Elm327LiveDataSource(transport, protocol)
            // Attached before connect() so a failed attempt's trace is still exportable via the
            // connection-log button, instead of only ever seeing the last successful session's.
            dashboardViewModel.attachIoLog(source.ioLog)
            source.connect()
            dashboardViewModel.useSource(source)
            dtcViewModel.attachClient(source.client)
            _connectionStatus.value = "Connected: ${transport.name}"
            return true
        } catch (e: Exception) {
            _connectionStatus.value = "Connection failed: ${describeError(e)} \u2014 see connection log for detail"
            return false
        }
    }

    fun useSimulated() {
        reconnectFactory = null
        dashboardViewModel.useSource(SimulatedLiveDataSource())
        _connectionStatus.value = "Simulated data"
    }

    // --- Auto-reconnect ---
    // Remembers how to re-open the last-used transport (Wi-Fi host/port, or the same Bluetooth
    // device/socket) so a dropped connection can be retried automatically instead of requiring
    // the driver to reach for the phone and tap Connect again mid-drive.
    private var reconnectFactory: (() -> Transport)? = null
    private var reconnecting = false

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
}
