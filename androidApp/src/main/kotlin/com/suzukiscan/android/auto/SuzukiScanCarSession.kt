package com.suzukiscan.android.auto

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Action
import com.suzukiscan.android.AutoCrashLog

class SuzukiScanCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = try {
        AutoCrashLog.append("Android Auto dashboard screen requested")
        DashboardCarScreen(carContext)
    } catch (e: Throwable) {
        AutoCrashLog.append("Android Auto screen creation failed", e)
        object : Screen(carContext) {
            override fun onGetTemplate() = MessageTemplate.Builder("Android Auto failed to start. See Debug logs on the phone.")
                .setTitle("pj-viewer")
                .setHeaderAction(Action.APP_ICON)
                .build()
        }
    }
}
