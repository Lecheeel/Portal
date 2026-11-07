package com.system.location.service.hook

import com.system.location.service.hook.runtime.HookRuntime
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/** System hooks wait for the framework's system-server classloader. */
class PortalModule : XposedModule() {
    private var processName = ""
    private var systemServer = false
    private val installedPackages = mutableSetOf<String>()

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        HookRuntime.attach(this)
        processName = param.processName
        systemServer = param.isSystemServer
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        install("system_server", "android", param.classLoader, 1000, 0)
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        // Boot hooks must not be stacked when a shared process loads another package.
        if (systemServer || !param.isFirstPackage) return
        install(processName.ifBlank { param.packageName }, param.packageName,
            param.classLoader, param.applicationInfo.uid, param.applicationInfo.flags)
    }

    @Synchronized
    private fun install(process: String, pkg: String, loader: ClassLoader, uid: Int, flags: Int) {
        if (pkg in installedPackages) return
        HookRuntime.attach(this)
        FakeLocation().install(process, pkg, loader, uid, flags)
        installedPackages.add(pkg)
    }

    // Timers, listeners and native state live for the process lifetime. Reject manual reload too.
    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean = false
}
