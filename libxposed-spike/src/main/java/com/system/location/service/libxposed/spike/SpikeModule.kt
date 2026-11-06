package com.system.location.service.libxposed.spike

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/** P6-01: minimal modern-API entry packaged into a standalone spike APK. */
class SpikeModule : XposedModule() {
    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        // No hooks yet; this APK verifies entry metadata and packaging.
    }
}
