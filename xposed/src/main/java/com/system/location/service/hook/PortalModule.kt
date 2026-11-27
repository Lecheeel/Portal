package com.system.location.service.hook

import android.os.Build
import android.os.Bundle
import com.system.location.service.hook.runtime.HookRuntime
import com.system.location.service.hook.scope.HookStatusRegistry
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import com.system.location.service.core.gnss.GnssBundledOrbitResources
import com.system.location.service.core.runtime.RuntimeTransferWire
import com.system.location.service.hook.runtime.ReloadExecutionGate
import com.system.location.service.hook.runtime.ReloadPreparation
import com.system.location.service.hook.hooks.LocationServiceHook
import com.system.location.service.hook.hooks.gnss.GnssListenerRouting
import com.system.location.service.hook.hooks.gnss.RawGnssCallbacks
import com.system.location.service.hook.hooks.nmea.NmeaListenerRouting
import com.system.location.service.hook.hooks.nmea.installNmeaCallback
import com.system.location.service.hook.runtime.CallbackSubscription
import com.system.location.service.hook.utils.FakeLoc

/** System hooks wait for the framework's system-server classloader. */
class PortalModule : XposedModule() {
    private val supportedPlatform = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    private var processName: String = ""
    private val lifecycle = ModuleInstallScheduler(installSystemPackageHooks = { pkg, loader ->
        HookRuntime.attach(this)
        HookRuntime.log("shared system package ready: package=$pkg loader=${loader.javaClass.name}")
        val installed = runCatching {
            when (pkg) {
                "com.android.providers.settings" -> com.system.location.service.hook.privacy.SettingsHook.hook(loader)
                "com.android.location.fused" -> com.system.location.service.hook.hooks.fused.AndroidFusedLocationProviderHook(loader)
                else -> false
            }
        }.onFailure { HookRuntime.log(it) }.getOrDefault(false)
        HookRuntime.log("shared system package hooks: package=$pkg installed=$installed")
        installed
    }) { process, pkg, loader, uid, flags ->
        HookRuntime.attach(this)
        FakeLocation().install(process, pkg, loader, uid, flags)
    }

    private fun isSystemProcess(): Boolean =
        lifecycle.isSystemServer ||
            processName == "system" || processName == "system_server" ||
            currentProcessName() == "system" || currentProcessName() == "system_server"

    private fun currentProcessName(): String = runCatching {
        java.io.File("/proc/self/cmdline").readText().trimEnd('\u0000')
    }.getOrDefault("")

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        if (!supportedPlatform) {
            android.util.Log.i("PortalModule", "System hooks require Android 12 or newer")
            return
        }
        processName = param.processName
        moduleInfo = runCatching { getModuleApplicationInfo() }.getOrNull()
        GnssBundledOrbitResources.installExternalReader(::readModuleResource)
        HookRuntime.attach(this)
        lifecycle.moduleLoaded(param.processName, param.isSystemServer)
        android.util.Log.i("PortalModule", "Module loaded: process=${param.processName} system=${param.isSystemServer} api=$apiVersion")
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        if (!supportedPlatform) return
        android.util.Log.i("PortalModule", "System server starting: loader=${param.classLoader.javaClass.name}")
        lifecycle.systemServerStarting(param.classLoader)
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (!supportedPlatform) return
        // The framework may report the shared android package before the actual phone/fused
        // package. Scope resolution, rather than isFirstPackage, decides which package to install.
        if (isSystemProcess()) {
            // Shared-process packages may only become visible through their own package loader.
            // Framework system scope covers these even when it removes their individual scopes.
            lifecycle.systemPackageReady(param.packageName, param.classLoader)
            return
        }
        android.util.Log.i("PortalModule", "Package ready: package=${param.packageName} first=${param.isFirstPackage}")
        if (moduleInfo == null) {
            moduleInfo = runCatching { getModuleApplicationInfo() }.getOrNull()
            GnssBundledOrbitResources.installExternalReader(::readModuleResource)
        }
        lifecycle.packageReady(param.packageName, param.classLoader,
            param.applicationInfo.uid, param.applicationInfo.flags)
    }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        if (!supportedPlatform) return false
        // Native Dobby hooks cannot be unloaded; require a process restart once they are active.
        if (!HotReloadPolicy.allowed(isSystemProcess(), NativeCommand.isLoaded)) {
            android.util.Log.i("PortalModule", "hot reload rejected: system=${isSystemProcess()} native=${NativeCommand.isLoaded} process=${currentProcessName()}")
            return false
        }
        val gate = HookRuntime.executionGate
        if (!runCatching { gate.freeze(2000) }.getOrElse { HookRuntime.log(it); false }) return false
        return ReloadPreparation.prepare(
            pauseLocation = { LocationTicker.stop() },
            pauseGnss = { com.system.location.service.hook.hooks.gnss.GnssSignalInjection.pause() },
            save = {
                val state = Bundle().apply {
                    putByteArray("runtime", RuntimeTransferWire.encode(FakeLoc.captureTransfer()))
                    putParcelableArrayList("location", ArrayList(LocationServiceHook.locationSubscriptions().map { it.toBundle() }))
                    putParcelableArrayList("gnss", ArrayList(GnssListenerRouting.subscriptions().map { it.toBundle() }))
                    putParcelableArrayList("nmea", ArrayList(NmeaListenerRouting.subscriptions().map { it.toBundle() }))
                    putParcelableArrayList("raw", ArrayList(RawGnssCallbacks.subscriptions().map { it.toBundle() }))
                    putParcelableArrayList("peers", ArrayList(ProxyCommand.subscriptions().map { it.toBundle() }))
                    putParcelableArrayList("carrier_listeners", com.system.location.service.hook.hooks.telephony
                        .CarrierRegistryHook.saveReloadState())
                    putLong("peer_sequence", ProxyCommand.publicationSequence())
                    putBundle("carrier_identity", com.system.location.service.hook.hooks.telephony
                        .CarrierConfigGlobalController.saveReloadState())
                    putString("process", "system_server")
                    putString("package", "android")
                    putInt("uid", 1000)
                    putInt("flags", 0)
                }
                // This API transfers objects within this process; it does not parcel them.
                // Share the drained platform monitor/counters so queued old interceptors see
                // the successor generation. Copying them into a Bundle splits the gate.
                param.setSavedInstanceState(arrayOf(state, gate.transferState(), lifecycle.systemPackageLoaders()))
                // The saved subscriptions contain framework Binders, not old module objects.
                // Drop our old death recipients/mailboxes once the framework accepts the state;
                // otherwise BinderProxy would keep every retired classloader alive. Complete
                // all retirements even if one cleanup fails, without resuming a partial old table.
                listOf<() -> Unit>(
                    { LocationServiceHook.retireLocationSubscriptions() },
                    { GnssListenerRouting.clear() },
                    { NmeaListenerRouting.clear() },
                    { RawGnssCallbacks.clear() },
                    { ProxyCommand.retireSubscriptions() },
                    { com.system.location.service.hook.hooks.telephony.CarrierRegistryHook.retire() },
                ).forEach { retire -> runCatching(retire).onFailure { HookRuntime.log(it) } }
                android.util.Log.i("PortalModule", "hot reloading system_server")
        }, abort = {
            try { gate.resume() } finally {
                val configuration = FakeLoc.runtimeSnapshots.current().configuration
                try {
                    if (configuration.enabled) LocationTicker.start()
                } finally {
                    if (configuration.gnssEnabled) com.system.location.service.hook.hooks.gnss.GnssSignalInjection.ensureStarted()
                }
            }
        })
    }

    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        if (!supportedPlatform) return
        if (!param.isSystemServer && !isSystemProcess()) return
        val transferred = param.getSavedInstanceState()
        val handoff = transferred as? Array<*>
        // Preserve configuration/subscriptions when upgrading from the previous Bundle format.
        val saved = handoff?.getOrNull(0) as? Bundle ?: transferred as? Bundle
        @Suppress("UNCHECKED_CAST")
        val gateState = handoff?.getOrNull(1) as? Array<Any>
        try {
            if (gateState != null) HookRuntime.executionGate = ReloadExecutionGate.adopt(gateState)
            param.oldHookHandles.forEach { runCatching { it.unhook() } }
            HookRuntime.attach(this)
            HookRuntime.clearHooks()
            HookStatusRegistry.reset()
            val loader = ClassLoader.getSystemClassLoader()
            android.util.Log.i("PortalModule", "hot reloaded system_server loader=${loader.javaClass.name}")
            saved?.getByteArray("runtime")?.let {
                val restored = FakeLoc.restoreRuntime(RuntimeTransferWire.decode(it), android.os.SystemClock.elapsedRealtimeNanos())
                SampleCommand.restoreLease(restored.state.sample, android.os.SystemClock.elapsedRealtimeNanos())
            }
            com.system.location.service.hook.hooks.telephony.CarrierConfigGlobalController
                .restoreReloadState(saved?.getBundle("carrier_identity"))
            lifecycle.systemServerStarting(loader)
            lifecycle.restoreSystemPackages(handoff?.getOrNull(2))
            com.system.location.service.hook.hooks.telephony.CarrierRegistryHook
                .restoreReloadState(saved?.getParcelableArrayList<Bundle>("carrier_listeners").orEmpty())
            restoreCallbacks(saved)
            ProxyCommand.restorePublicationSequence(saved?.getLong("peer_sequence") ?: 0L)
            restorePeers(saved)
            if (FakeLoc.runtimeSnapshots.current().configuration.enabled) LocationTicker.start()
            if (FakeLoc.runtimeSnapshots.current().configuration.gnssEnabled) com.system.location.service.hook.hooks.gnss.GnssSignalInjection.ensureStarted()
        } finally {
            // A restore error must not leave every old-snapshot call blocked forever.
            runCatching { HookRuntime.executionGate.resume() }
        }
    }

    private fun restoreCallbacks(saved: Bundle?) {
        fun rows(key: String) = saved?.getParcelableArrayList<Bundle>(key).orEmpty().mapNotNull {
            runCatching { CallbackSubscription.fromBundle(it) }.getOrNull()
        }
        rows("location").let(LocationServiceHook::restoreLocationSubscriptions)
        rows("gnss").forEach { if (GnssListenerRouting.restore(it)) LocationServiceHook.installGnssStatusCallback(it.listener) }
        rows("nmea").forEach { if (NmeaListenerRouting.restore(it)) installNmeaCallback(it.listener) }
        rows("raw").forEach(RawGnssCallbacks::restoreAndInstall)
    }

    /** Read a bundled core resource from the module APK when the injected loader has no resources. */
    private fun moduleApkPath(): String? = moduleInfo?.sourceDir ?: runCatching {
        val context = com.system.location.service.hook.utils.BinderUtils.getSystemContext()
            ?: return@runCatching null
        val packageName = javaClass.packageName.substringBeforeLast('.')
        context.packageManager.getApplicationInfo(packageName, 0).sourceDir
    }.getOrNull()

    private fun readModuleResource(name: String): String? = runCatching {
        val path = moduleApkPath() ?: return@runCatching null
        java.util.zip.ZipFile(path).use { zip ->
            val entry = zip.getEntry("gnss/$name") ?: return@runCatching null
            zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }.getOrNull()

    companion object {
        /** Module APK metadata; used to locate the opt-in anti-detect payload. */
        @Volatile
        var moduleInfo: android.content.pm.ApplicationInfo? = null
            private set
    }

    private fun restorePeers(saved: Bundle?) {
        saved?.getParcelableArrayList<Bundle>("peers").orEmpty().forEach { row ->
            @Suppress("DEPRECATION")
            val binder = row.getBinder("binder") ?: return@forEach
            val subscription = RuntimePeerRegistry.Subscription(
                row.getInt("uid"), row.getString("name") ?: return@forEach,
                row.getString("session") ?: return@forEach, binder,
            )
            ProxyCommand.restore(subscription)
        }
        ProxyCommand.publishCurrent()
    }
}

/**
 * Hot reload is safe only where hook state and generation cleanup are process-local. system_server
 * owns that lifecycle; app processes still need a restart. Native Dobby hooks cannot be unloaded.
 */
internal object HotReloadPolicy {
    fun allowed(isSystemProcess: Boolean, nativeLoaded: Boolean): Boolean =
        isSystemProcess && !nativeLoaded
}

/** Keeps lifecycle ordering and package idempotence independent of Android installation details. */
internal class ModuleInstallScheduler(
    private val installSystemPackageHooks: (String, ClassLoader) -> Boolean = { _, _ -> false },
    private val installHooks: (String, String, ClassLoader, Int, Int) -> Unit,
) {
    private var processName = ""
    var isSystemServer = false
        private set
    private val installedPackages = mutableSetOf<String>()
    private val readySystemPackages = mutableMapOf<String, ClassLoader>()
    private val installedSystemPackages = mutableSetOf<String>()

    fun moduleLoaded(process: String, systemServer: Boolean) {
        processName = process
        isSystemServer = systemServer
    }

    @Synchronized
    fun systemServerStarting(loader: ClassLoader) {
        install("system_server", "android", loader, 1000, 0)
        readySystemPackages.toMap().forEach { (pkg, packageLoader) -> systemPackageReady(pkg, packageLoader) }
    }

    @Synchronized
    fun systemPackageReady(pkg: String, loader: ClassLoader) {
        if (pkg !in setOf("com.android.providers.settings", "com.android.location.fused")) return
        readySystemPackages[pkg] = loader
        if ("android" !in installedPackages || pkg in installedSystemPackages) return
        if (installSystemPackageHooks(pkg, loader)) installedSystemPackages.add(pkg)
    }

    /** Framework handoff stays within this process; only platform types cross module generations. */
    @Synchronized
    fun systemPackageLoaders(): Array<Array<Any>> =
        readySystemPackages.map { (pkg, loader) -> arrayOf<Any>(pkg, loader) }.toTypedArray()

    fun restoreSystemPackages(state: Any?) {
        (state as? Array<*>)?.forEach { entry ->
            val pair = entry as? Array<*> ?: return@forEach
            val pkg = pair.getOrNull(0) as? String ?: return@forEach
            val loader = pair.getOrNull(1) as? ClassLoader ?: return@forEach
            systemPackageReady(pkg, loader)
        }
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
