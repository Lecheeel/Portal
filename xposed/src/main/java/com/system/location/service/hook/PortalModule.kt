package com.system.location.service.hook

import com.system.location.service.hook.runtime.HookRuntime
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/** System hooks wait for the framework's system-server classloader. */
class PortalModule : XposedModule() {
    private val lifecycle = ModuleInstallScheduler { process, pkg, loader, uid, flags ->
        HookRuntime.attach(this)
        FakeLocation().install(process, pkg, loader, uid, flags)
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        HookRuntime.attach(this)
        lifecycle.moduleLoaded(param.processName, param.isSystemServer)
        android.util.Log.i("PortalModule", "Module loaded: process=${param.processName} system=${param.isSystemServer} api=$apiVersion")
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        android.util.Log.i("PortalModule", "System server starting: loader=${param.classLoader.javaClass.name}")
        lifecycle.systemServerStarting(param.classLoader)
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        // The framework may report the shared android package before the actual phone/fused
        // package. Scope resolution, rather than isFirstPackage, decides which package to install.
        if (lifecycle.isSystemServer) return
        android.util.Log.i("PortalModule", "Package ready: package=${param.packageName} first=${param.isFirstPackage}")
        lifecycle.packageReady(param.packageName, param.classLoader,
            param.applicationInfo.uid, param.applicationInfo.flags)
    }

    // Timers, listeners and native state live for the process lifetime. Reject manual reload too.
    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean = false
}

/** Keeps lifecycle ordering and package idempotence independent of Android installation details. */
internal class ModuleInstallScheduler(
    private val installHooks: (String, String, ClassLoader, Int, Int) -> Unit,
) {
    private var processName = ""
    var isSystemServer = false
        private set
    private val installedPackages = mutableSetOf<String>()

    fun moduleLoaded(process: String, systemServer: Boolean) {
        processName = process
        isSystemServer = systemServer
    }

    fun systemServerStarting(loader: ClassLoader) {
        install("system_server", "android", loader, 1000, 0)
    }

    fun packageReady(pkg: String, loader: ClassLoader, uid: Int, flags: Int) {
        if (isSystemServer) return
        install(processName.ifBlank { pkg }, pkg, loader, uid, flags)
    }

    @Synchronized
    private fun install(process: String, pkg: String, loader: ClassLoader, uid: Int, flags: Int) {
        if (pkg in installedPackages) return
        installHooks(process, pkg, loader, uid, flags)
        installedPackages.add(pkg)
    }
}
