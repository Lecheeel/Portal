package com.system.location.service

import android.app.Application
import android.content.Context
import com.system.location.service.privacy.SdkPrivacy

open class LocationServiceApp: Application() {

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        com.system.location.service.runtime.XposedServiceBridge.register()
        // The receiver must not initialize playback or clean the main process's providers.
        if (getProcessName() == "$packageName:location_probe") return

        SdkPrivacy.initialize(this)

        com.system.location.service.runtime.ScenarioRuntime.initialize()

        // Optional SDK restore failures are isolated by SdkPrivacy; runtime remains usable.
    }

    companion object {
        lateinit var appContext: Context
    }
}
