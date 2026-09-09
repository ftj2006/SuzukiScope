package com.suzukiscope.ui

import com.suzukiscope.core.field.FieldDefinition

/** Platform-specific way to get the driver's attention when a field crosses its critical threshold
 * (e.g. vibration + tone on Android). Fires once per threshold crossing, not on every poll. */
fun interface CriticalAlertSink {
    fun onCritical(field: FieldDefinition, value: Double)
}
