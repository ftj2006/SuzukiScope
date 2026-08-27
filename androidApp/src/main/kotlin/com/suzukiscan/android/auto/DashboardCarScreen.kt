package com.suzukiscan.android.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.suzukiscan.android.AppState
import com.suzukiscan.android.transport.findBondedElm327Device
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val REFRESH_MS = 300L

/**
 * Android Auto dashboard: a 3-per-row grid of gauges mirroring whichever fields are
 * enabled/ordered on the phone (configuration itself only happens in the phone app's Config
 * tab — this screen just shows the result), plus Connect and Start/Stop logging controls in
 * the ActionStrip, backed by the same app-wide AppState the phone Activity uses.
 */
class DashboardCarScreen(carContext: CarContext) : Screen(carContext) {

    init {
        // Car head unit resolution/density varies a lot and isn't knowable from a photo of the
        // screen, so log it once per connection to size the grid layout from real numbers instead
        // of guessing — see Documents/pj-viewer/Debug/android-auto-crash.log on the phone.
        val metrics = carContext.resources.displayMetrics
        com.suzukiscan.android.AutoCrashLog.append(
            "Android Auto screen: ${metrics.widthPixels}x${metrics.heightPixels}px, " +
                "density=${metrics.density} (${metrics.densityDpi}dpi)",
        )
    }

    // A single poll cycle emits one value per enabled field in quick succession (a handful of
    // milliseconds apart) even though it was one real request/response — invalidating on every
    // one of those would rebuild the whole template (re-rendering all 6 gauge bitmaps) up to 6x
    // more than needed and make the car host's redraw noticeably laggy. This coalesces bursts of
    // updates into at most one redraw per REFRESH_MS.
    private var dirty = false

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                lifecycleScope.launch {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        launch { AppState.connectionStatus.collect { dirty = true } }
                        launch { AppState.dashboardViewModel.values.collect { dirty = true } }
                        launch { AppState.dashboardViewModel.peaks.collect { dirty = true } }
                        launch { AppState.dashboardViewModel.isLogging.collect { dirty = true } }
                        launch {
                            while (true) {
                                delay(REFRESH_MS)
                                if (dirty) {
                                    dirty = false
                                    invalidate()
                                }
                            }
                        }
                    }
                }
            }
        })
    }

    override fun onGetTemplate(): Template = try {
        buildTemplate()
    } catch (e: Throwable) {
        com.suzukiscan.android.AutoCrashLog.append("Android Auto dashboard template failed", e)
        androidx.car.app.model.MessageTemplate.Builder("Android Auto dashboard failed. See Debug logs on the phone.")
            .setTitle("pj-viewer")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }

    private fun buildTemplate(): Template {
        val fields = AppState.dashboardViewModel.fields.value.filter { it.enabled }
        val values = AppState.dashboardViewModel.values.value
        val peaks = AppState.dashboardViewModel.peaks.value
        val isLogging = AppState.dashboardViewModel.isLogging.value
        val isConnected = AppState.dashboardViewModel.isUsingHardwareSource()

        val itemListBuilder = ItemList.Builder()
        for (field in fields) {
            val value = values[field.id] ?: field.gaugeMin
            itemListBuilder.addItem(
                GridItem.Builder()
                    // No title: GridItem's title is optional, and every pixel the host reserves for
                    // it is a pixel not spent on the gauge image itself — the label is baked into
                    // the bitmap instead (see GaugeIcon), so the ring can use the whole tile.
                    .setImage(
                        GaugeIcon.render(
                            field.label,
                            field.unit,
                            value,
                            field.gaugeMin,
                            field.gaugeMax,
                            peaks[field.id],
                            field.warningThreshold,
                            field.criticalThreshold,
                            field.decimals,
                        ),
                        GridItem.IMAGE_TYPE_LARGE,
                    )
                    .build(),
            )
        }

        val actionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setIcon(GaugeIcon.loggingStatusIcon(isLogging))
                    .setOnClickListener {
                        if (isLogging) AppState.dashboardViewModel.stopLogging() else AppState.dashboardViewModel.startLogging()
                        invalidate()
                    }
                    .build(),
            )
            .addAction(
                Action.Builder()
                    .setIcon(GaugeIcon.connectionStatusIcon(isConnected))
                    .setOnClickListener { lifecycleScope.launch { AppState.toggleConnection() } }
                    .build(),
            )
            .build()

        return GridTemplate.Builder()
            .setTitle(compactStatus(AppState.connectionStatus.value, isLogging))
            .setHeaderAction(Action.APP_ICON)
            .setItemSize(GridTemplate.ITEM_SIZE_LARGE)
            .setItemImageShape(GridTemplate.ITEM_IMAGE_SHAPE_CIRCLE)
            .setSingleList(itemListBuilder.build())
            .setActionStrip(actionStrip)
            .build()
    }

    /** Car template titles are host-styled (large, fixed size) so the only lever we have to make
     * the heading less dominant is keeping its text as short as possible — connection and
     * logging state share this one line since detailed config/logging management lives on the
     * phone, not on this screen. */
    private fun compactStatus(status: String, isLogging: Boolean): String {
        val base = when {
            status.startsWith("Connected: WiFi") -> "Wi-Fi"
            status.startsWith("Connected:") -> "Connected"
            status.contains("Connecting", ignoreCase = true) || status.contains("initialising", ignoreCase = true) -> "Connecting\u2026"
            status.contains("Simulated", ignoreCase = true) -> "Test data"
            status.contains("Disconnected", ignoreCase = true) -> "Disconnected"
            status.contains("failed", ignoreCase = true) || status.contains("lost", ignoreCase = true) || status.contains("error", ignoreCase = true) -> "Connection error"
            else -> status.take(20)
        }
        return if (isLogging) "$base \u00b7 REC" else base
    }
}
