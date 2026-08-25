package com.suzukiscan.android

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.suzukiscan.core.field.FieldDefinition
import com.suzukiscan.ui.WarningAlertSink

/** Vibration + short tone when a gauge crosses its warning threshold — meant to be noticeable
 * without needing to look at the screen while driving. */
class AndroidWarningAlertSink(context: Context) : WarningAlertSink {
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    private val toneGenerator = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 80) }.getOrNull()

    override fun onWarning(field: FieldDefinition, value: Double) {
        vibrator?.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
        toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200)
    }
}
