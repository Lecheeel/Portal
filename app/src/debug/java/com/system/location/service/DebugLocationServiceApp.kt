package com.system.location.service

import com.system.location.service.debug.DebugHttpServer

/** Debug-only application entry: production never packages the server or this subclass. */
class DebugLocationServiceApp : LocationServiceApp() {
    override fun onCreate() {
        super.onCreate()
        DebugHttpServer.start(this)
    }
}
