package com.suzukiscan.android.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.suzukiscan.android.AppState
import com.suzukiscan.android.transport.findBondedElm327Device
import kotlinx.coroutines.launch

/**
 * Android Auto dashboard: read-only display of whichever fields are enabled/ordered on the
 * phone (configuration itself only happens in the phone app's Config tab — this screen just
 * shows the result), plus Connect and Start/Stop logging controls backed by the same
 * app-wide AppState the phone Activity uses.
 */
class DashboardCarScreen(carContext: CarContext) : Screen(carContext) {

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                lifecycleScope.launch { AppState.connectionStatus.collect { invalidate() } }
                lifecycleScope.launch { AppState.dashboardViewModel.values.collect { invalidate() } }
                lifecycleScope.launch { AppState.dashboardViewModel.isLogging.collect { invalidate() } }
            }
        })
    }

    override fun onGetTemplate(): Template {
        val fields = AppState.dashboardViewModel.fields.value.filter { it.enabled }
        val values = AppState.dashboardViewModel.values.value
        val isLogging = AppState.dashboardViewModel.isLogging.value

        val itemListBuilder = ItemList.Builder()
        itemListBuilder.addItem(
            Row.Builder()
                .setTitle("Connection")
                .addText(AppState.connectionStatus.value)
                .build(),
        )
        itemListBuilder.addItem(
            Row.Builder()
                .setTitle(if (isLogging) "Logging: ON" else "Logging: OFF")
                .addText(if (isLogging) "${AppState.dashboardViewModel.recorder.readings().size} samples recorded" else "Not recording")
                .build(),
        )
        for (field in fields) {
            val value = values[field.id]
            val text = if (value != null) "%.1f %s".format(value, field.unit) else "\u2013"
            itemListBuilder.addItem(Row.Builder().setTitle(field.label).addText(text).build())
        }

        val actionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle(if (isLogging) "Stop logging" else "Start logging")
                    .setOnClickListener {
                        if (isLogging) AppState.dashboardViewModel.stopLogging() else AppState.dashboardViewModel.startLogging()
                        invalidate()
                    }
                    .build(),
            )
            .addAction(
                Action.Builder()
                    .setTitle("Connect Wi-Fi")
                    .setOnClickListener { lifecycleScope.launch { AppState.connectWifi() } }
                    .build(),
            )
            .addAction(
                Action.Builder()
                    .setTitle("Connect BT")
                    .setOnClickListener {
                        val connectNow = {
                            val device = findBondedElm327Device(carContext)
                            if (device == null) {
                                AppState.setStatus("No paired Bluetooth devices found")
                            } else {
                                lifecycleScope.launch {
                                    AppState.connectBluetooth(com.suzukiscan.android.transport.BluetoothSppTransport(device))
                                }
                            }
                        }
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
                            androidx.core.content.ContextCompat.checkSelfPermission(
                                carContext,
                                android.Manifest.permission.BLUETOOTH_CONNECT,
                            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            carContext.requestPermissions(listOf(android.Manifest.permission.BLUETOOTH_CONNECT)) { granted, _ ->
                                if (granted.isNotEmpty()) connectNow()
                            }
                        } else {
                            connectNow()
                        }
                    }
                    .build(),
            )
            .addAction(
                Action.Builder()
                    .setTitle("Codes")
                    .setOnClickListener { screenManager.push(DtcCarScreen(carContext)) }
                    .build(),
            )
            .build()

        return ListTemplate.Builder()
            .setTitle("Suzuki Scan")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(itemListBuilder.build())
            .setActionStrip(actionStrip)
            .build()
    }
}
