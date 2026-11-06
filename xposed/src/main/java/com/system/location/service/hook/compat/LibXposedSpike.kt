package com.system.location.service.hook.compat

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * P6-01 compile-time spike: proves libxposed API 102 can be compiled alongside the
 * legacy API 82 module. This class is not registered as the module entry point yet.
 */
@Suppress("unused")
class LibXposedSpike : XposedModule() {
    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        // Intentionally empty: this spike only verifies API availability and linkage.
    }

    companion object {
        const val API = XposedInterface.API_102
    }
}
