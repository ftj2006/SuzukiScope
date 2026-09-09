package com.suzukiscope.android

import android.app.Application
import java.io.File

class PjViewerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AutoCrashLog.init(this)
        AutoCrashLog.append("Application started")
        // Runs here (not just MainActivity.onCreate) because Android Auto can launch this
        // process directly, without the phone Activity ever starting — field selection/Wi-Fi
        // config/auto-reconnect must be ready before the first car screen is built either way.
        AppState.initPersistence(File(filesDir, "field-config.json"))
        AppState.initWifiConfig(File(filesDir, "wifi-config.json"))
        AppState.initLastConnection(File(filesDir, "last-connection.txt"))
        AppState.initDtcModulesPersistence(File(filesDir, "dtc-unresponsive-modules.txt"))
        AppState.initAutoProbe(File(filesDir, "auto-probe-done.txt"))
        AppState.initAlerts(this)
        AppState.startup()
    }
}
