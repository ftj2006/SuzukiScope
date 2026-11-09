package com.suzukiscope.android.auto

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.OnClickListener
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridSection
import androidx.car.app.model.SectionedItemTemplate
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
import com.suzukiscope.core.layout.Placement
import com.suzukiscope.core.layout.TileSize
import com.suzukiscope.core.layout.arrange
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val REFRESH_MS = 150L
private const val PERF_WINDOW_MS = 10_000L
private const val JITTER_FRACTION = 0.02
private const val JITTER_BLEND = 0.5

// Tiles per row the host uses for ITEM_SIZE_LARGE on the tested head unit; it isn't queryable.
private const val HOST_COLUMNS = 5

/**
 * Grid slots (gauge index, or null for an invisible spacer) that spread [count] gauges evenly
 * over the rows they need and centre each row, e.g. 6 across 5 columns -> _ 0 1 2 _ / _ 3 4 5 _.
 */
internal fun centredSlots(count: Int, columns: Int): List<Int?> {
    if (count == 0) return emptyList()
    val rows = (count + columns - 1) / columns
    val slots = ArrayList<Int?>()
    var next = 0
    for (row in 0 until rows) {
        val inRow = count / rows + if (row < count % rows) 1 else 0
        val left = (columns - inRow) / 2
        repeat(left) { slots.add(null) }
        repeat(inRow) { slots.add(next++) }
        if (row < rows - 1) repeat(columns - inRow - left) { slots.add(null) }
    }
    return slots
}

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

    // Redraw statistics, written to the connection log every PERF_WINDOW_MS as an "AA PERF" line.
    private var invalidatedAt = 0L
    private var invalidations = 0
    private var redraws = 0
    private var buildTotalMs = 0L
    private var buildMaxMs = 0L
    private var waitTotalMs = 0L
    private var waitMaxMs = 0L
    private var windowStart = 0L

    /** [waited] is how long the host took to ask for the template after we invalidated. */
    private fun recordRedraw(start: Long, waited: Long?) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (windowStart == 0L) windowStart = start
        val build = now - start
        redraws++
        buildTotalMs += build
        buildMaxMs = maxOf(buildMaxMs, build)
        waited?.let { waitTotalMs += it; waitMaxMs = maxOf(waitMaxMs, it) }
        if (now - windowStart < PERF_WINDOW_MS) return
        AppState.dashboardViewModel.logConnectionEvent(
            "AA PERF ${(now - windowStart) / 1000.0}s: $redraws redraws for $invalidations requests (every $REFRESH_MS ms); " +
                "build avg ${buildTotalMs / redraws} ms max $buildMaxMs ms; host wait avg ${waitTotalMs / redraws} ms max $waitMaxMs ms",
        )
        invalidations = 0
        redraws = 0
        buildTotalMs = 0
        buildMaxMs = 0
        waitTotalMs = 0
        waitMaxMs = 0
        windowStart = now
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                lifecycleScope.launch {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        launch { AppState.connectionStatus.collect { dirty = true } }
                        launch { AppState.dashboardViewModel.values.collect { dirty = true } }
                        launch { AppState.dashboardViewModel.fields.collect { dirty = true } }
                        launch { AppState.dashboardViewModel.autoLayout.collect { dirty = true } }
                        launch { AppState.dashboardViewModel.peaks.collect { dirty = true } }
                        launch { AppState.dashboardViewModel.staleFields.collect { dirty = true } }
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
                                // Flash toggles every 600 ms regardless of the refresh interval.
                                if (AppState.dashboardViewModel.isLogging.value && ++tick % (600L / REFRESH_MS).toInt() == 0) {
                                    flashOn = !flashOn
                                    dirty = true
                                }
                                if (dirty) {
                                    dirty = false
                                    if (invalidatedAt == 0L) invalidatedAt = android.os.SystemClock.elapsedRealtime()
                                    invalidations++
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
        val start = android.os.SystemClock.elapsedRealtime()
        val waited = if (invalidatedAt > 0L) start - invalidatedAt else null
        invalidatedAt = 0L
        buildTemplate().also { recordRedraw(start, waited) }
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
        val staleFields = AppState.dashboardViewModel.staleFields.value
        val isLogging = AppState.dashboardViewModel.isLogging.value
        val isConnected = AppState.dashboardViewModel.isUsingHardwareSource()

        val itemListBuilder = ItemList.Builder()
        val gaugeItems = fields.map { field ->
            GridItem.Builder()
                // No title (optional since car-app 1.7): the label is baked into the GaugeIcon bitmap.
                .setImage(
                    GaugeIcon.render(
                        field.label,
                        field.unit,
                        shownValue(field, values[field.id]),
                        field.gaugeMin,
                        field.gaugeMax,
                        peaks[field.id],
                        field.warningThreshold,
                        field.criticalThreshold,
                        field.decimals,
                        field.id in staleFields,
                    ),
                    GridItem.IMAGE_TYPE_LARGE,
                )
                .build()
        }
        val gridLimit = runCatching {
            carContext.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID)
        }.getOrDefault(fields.size)
        val padded = centredSlots(fields.size, HOST_COLUMNS)
        // Never let padding push real gauges past the host's item limit.
        val slots = if (padded.size <= gridLimit) padded else fields.indices.toList()
        for (slot in slots) {
            itemListBuilder.addItem(
                if (slot == null) GridItem.Builder().setImage(GaugeIcon.blankIcon(), GridItem.IMAGE_TYPE_LARGE).build() else gaugeItems[slot],
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
            val recordFab = Action.Builder()
                .setIcon(GaugeIcon.loggingStatusIcon(isLogging))
                .setBackgroundColor(if (isLogging && flashOn) CarColor.RED else CarColor.SECONDARY)
                .setOnClickListener(onRecord)
                .build()
            val connectFab = Action.Builder()
                .setIcon(GaugeIcon.connectionStatusIcon(isConnected))
                .setBackgroundColor(connectionColor(status, isConnected))
                .setOnClickListener(onConnect)
                .build()
            // Floating buttons cover the bottom-right gauge, so only show the ones that are useful right now.
            val linkUp = connectionColor(status, isConnected) == CarColor.GREEN
            val fabs = listOfNotNull(recordFab.takeIf { linkUp || isLogging }, connectFab.takeIf { !linkUp })

            // The plain grid's largest tile leaves most of the screen empty with only a few gauges;
            // the sectioned grid (Car API 8) offers an extra-large tile size.
            if (carContext.carAppApiLevel >= 8 && fields.isNotEmpty()) {
                // Rows and tile sizes come from the Config tab; each row is its own section, padded to its full width.
                val builder = SectionedItemTemplate.Builder()
                    .setScrollStatePersistenceStrategy(SectionedItemTemplate.SCROLL_STATE_PRESERVE_INDEX)
                for (row in AppState.dashboardViewModel.autoLayout.value.arrange(fields.map { Placement(it.autoRow, it.autoSlot) })) {
                    builder.addSection(
                        GridSection.Builder()
                            .setItemSize(if (row.size == TileSize.EXTRA_LARGE) GridSection.ITEM_SIZE_EXTRA_LARGE else GridSection.ITEM_SIZE_LARGE)
                            .setItemImageShape(GridSection.ITEM_IMAGE_SHAPE_CIRCLE)
                            .setItems(row.slots.map { slot -> if (slot == null) blankItem() else gaugeItems[slot] })
                            .build(),
                    )
                }
                fabs.forEach { builder.addAction(it) }
                return builder.build()
            }

            // No title/header action/action strip hides the host's header bar entirely; the two
            // controls float over the grid instead, giving the gauges the full screen height.
            return GridTemplate.Builder()
                .setItemSize(GridTemplate.ITEM_SIZE_LARGE)
                .setItemImageShape(GridTemplate.ITEM_IMAGE_SHAPE_CIRCLE)
                .setSingleList(itemListBuilder.build())
                .apply { fabs.forEach { addAction(it) } }
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

    private fun blankItem(): GridItem = GridItem.Builder().setImage(GaugeIcon.blankIcon(), GridItem.IMAGE_TYPE_LARGE).build()

    private val shownValues = HashMap<String, Double>()

    // Adaptive smoothing: jitter within a small band is averaged, larger moves are shown at once so real inputs don't lag.
    private fun shownValue(field: com.suzukiscope.core.field.FieldDefinition, raw: Double?): Double {
        if (raw == null) {
            shownValues.remove(field.id)
            return field.gaugeMin
        }
        val previous = shownValues[field.id]
        val band = (field.gaugeMax - field.gaugeMin) * JITTER_FRACTION
        val shown = if (previous == null || kotlin.math.abs(raw - previous) > band) raw else previous + (raw - previous) * JITTER_BLEND
        shownValues[field.id] = shown
        return shown
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
