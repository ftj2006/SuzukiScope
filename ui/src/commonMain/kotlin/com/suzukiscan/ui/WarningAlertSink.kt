package com.suzukiscan.ui

import com.suzukiscan.core.field.FieldDefinition

/** Platform-specific way to get the driver's attention when a field crosses its warning threshold
 * (e.g. vibration + tone on Android). Fires once per threshold crossing, not on every poll. */
fun interface WarningAlertSink {
    fun onWarning(field: FieldDefinition, value: Double)
}
