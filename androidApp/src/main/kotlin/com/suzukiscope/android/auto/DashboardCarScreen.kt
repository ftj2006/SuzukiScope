package com.suzukiscope.android.auto

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.OnClickListener
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.suzukiscope.android.AppState
import com.suzukiscope.android.transport.findBondedElm327Device
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
        // of guessing — see Documents/SuzukiScope/Debug/android-auto-crash.log on the phone.
        val metrics = carContext.resources.displayMetrics
        com.suzukiscope.android.AutoCrashLog.append(
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
    private var flashOn = true

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
                            AppState.recordingSaved.collect { path ->
                                CarToast.makeText(carContext, "Recording saved: $path", CarToast.LENGTH_LONG).show()
                            }
                        }
                        launch {
                            var tick = 0
                            while (true) {
                                delay(REFRESH_MS)
                                if (AppState.dashboardViewModel.isLogging.value && ++tick % 2 == 0) {
                                    flashOn = !flashOn
                                    dirty = true
                                }
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
        com.suzukiscope.android.AutoCrashLog.append("Android Auto dashboard template failed", e)
        androidx.car.app.model.MessageTemplate.Builder("Android Auto dashboard failed. See Debug logs on the phone.")
            .setTitle("SuzukiScope")
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
                    // No title (optional since car-app 1.7): the label is baked into the GaugeIcon bitmap.
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

        val recordIcon = if (isLogging) GaugeIcon.recordingFlashIcon(flashOn) else GaugeIcon.loggingStatusIcon(false)
        val onRecord = OnClickListener {
            if (isLogging) AppState.dashboardViewModel.stopLogging() else AppState.dashboardViewModel.startLogging()
            invalidate()
        }
        val onConnect = OnClickListener { lifecycleScope.launch { AppState.toggleConnection() } }
        val status = AppState.connectionStatus.value

        if (carContext.carAppApiLevel >= 7) {
            // No title/header action/action strip hides the host's header bar entirely; the two
            // controls float over the grid instead, giving the gauges the full screen height.
            return GridTemplate.Builder()
                .setItemSize(GridTemplate.ITEM_SIZE_LARGE)
                .setItemImageShape(GridTemplate.ITEM_IMAGE_SHAPE_CIRCLE)
                .setSingleList(itemListBuilder.build())
                .addAction(
                    Action.Builder()
                        .setIcon(GaugeIcon.loggingStatusIcon(isLogging))
                        .setBackgroundColor(if (isLogging && flashOn) CarColor.RED else CarColor.SECONDARY)
                        .setOnClickListener(onRecord)
                        .build(),
                )
                .addAction(
                    Action.Builder()
                        .setIcon(GaugeIcon.connectionStatusIcon(isConnected))
                        .setBackgroundColor(connectionColor(status, isConnected))
                        .setOnClickListener(onConnect)
                        .build(),
                )
                .build()
        }

        val actionStrip = ActionStrip.Builder()
            .addAction(Action.Builder().setIcon(recordIcon).setOnClickListener(onRecord).build())
            .addAction(Action.Builder().setIcon(GaugeIcon.connectionStatusIcon(isConnected)).setOnClickListener(onConnect).build())
            .build()

        return GridTemplate.Builder()
            .setTitle(compactStatus(status, isLogging))
            .setHeaderAction(Action.APP_ICON)
            .setItemSize(GridTemplate.ITEM_SIZE_LARGE)
            .setItemImageShape(GridTemplate.ITEM_IMAGE_SHAPE_CIRCLE)
            .setSingleList(itemListBuilder.build())
            .setActionStrip(actionStrip)
            .build()
    }

    /** With no header there's no status text, so the connect button's colour carries the state. */
    private fun connectionColor(status: String, isConnected: Boolean): CarColor = when {
        status.startsWith("Waiting for Wi-Fi") ||
            status.contains("Connecting", ignoreCase = true) ||
            status.contains("reconnecting", ignoreCase = true) -> CarColor.YELLOW
        isConnected -> CarColor.GREEN
        else -> CarColor.SECONDARY
    }

    /** Car template titles are host-styled (large, fixed size) so the only lever we have to make
     * the heading less dominant is keeping its text as short as possible — connection and
     * logging state share this one line since detailed config/logging management lives on the
     * phone, not on this screen. */
    private fun compactStatus(status: String, isLogging: Boolean): String {
        val base = when {
            status.startsWith("Connected: WiFi") -> "Wi-Fi"
            status.startsWith("Connected:") -> "Connected"
            status.startsWith("Waiting for Wi-Fi") -> "Waiting for Wi-Fi"
            status.contains("Connecting", ignoreCase = true) || status.contains("initialising", ignoreCase = true) -> "Connecting\u2026"
            status.contains("Simulated", ignoreCase = true) -> "Test data"
            status.contains("Disconnected", ignoreCase = true) -> "Disconnected"
            status.contains("failed", ignoreCase = true) || status.contains("lost", ignoreCase = true) || status.contains("error", ignoreCase = true) -> "Connection error"
            else -> status.take(20)
        }
        return if (isLogging) "$base \u00b7 REC" else base
    }
}
