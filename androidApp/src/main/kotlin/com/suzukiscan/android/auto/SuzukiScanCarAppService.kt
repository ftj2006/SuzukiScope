package com.suzukiscan.android.auto

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.car.app.validation.HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

/**
 * Android Auto entry point (Car App Library, IOT category — vehicle status/monitoring).
 * NOTE: uses ALLOW_ALL_HOSTS_VALIDATOR for now (fine for Android Auto/dev testing); a production
 * release should restrict this to the real Android Auto host signature.
 */
class SuzukiScanCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator = ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = SuzukiScanCarSession()
}
