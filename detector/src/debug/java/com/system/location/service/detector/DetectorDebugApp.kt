package com.system.location.service.detector

import android.app.Application

/** Debug-only entry: starts the local detector HTTP bridge; never packaged in release. */
class DetectorDebugApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DetectorDebugServer.start(this)
    }
}
