package com.suzukiscope.android.auto

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator
import androidx.car.app.validation.HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
import com.suzukiscope.android.AppState
import com.suzukiscope.android.AutoCrashLog

/**
 * Android Auto entry point (Car App Library, IOT category — vehicle status/monitoring).
 * NOTE: uses ALLOW_ALL_HOSTS_VALIDATOR for now (fine for Android Auto/dev testing); a production
 * release should restrict this to the real Android Auto host signature.
 */
class SuzukiScopeCarAppService : CarAppService() {
    override fun onCreate() {
        super.onCreate()
        AutoCrashLog.append("Android Auto CarAppService created")
    }

    override fun createHostValidator(): HostValidator {
        AutoCrashLog.append("Android Auto host validator requested")
        return ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        AutoCrashLog.append("Android Auto session requested")
        return SuzukiScopeCarSession()
    }

    override fun onCreateSession(sessionInfo: SessionInfo): Session {
        AutoCrashLog.append("Android Auto session requested: $sessionInfo")
        return SuzukiScopeCarSession()
    }
}
