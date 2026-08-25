package com.suzukiscan.android.auto

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

class SuzukiScanCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = DashboardCarScreen(carContext)
}
