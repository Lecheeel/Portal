package com.system.location.service.hook

import android.os.Bundle
import com.system.location.service.hook.runtime.HookRuntime
import com.system.location.service.hook.scope.HookStatusRegistry
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
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
    private var processName: String = ""
    private val lifecycle = ModuleInstallScheduler { process, pkg, loader, uid, flags ->
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
        processName = param.processName
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
        if (isSystemProcess()) {
            // SettingsProvider can be hosted inside system_server and is only class-loadable when
            // its package is ready; the early system-server attempt cannot see it on such ROMs.
            if (param.packageName == "com.android.providers.settings") {
                com.system.location.service.hook.privacy.SettingsHook.hook(param.classLoader)
            }
            return
        }
        android.util.Log.i("PortalModule", "Package ready: package=${param.packageName} first=${param.isFirstPackage}")
        lifecycle.packageReady(param.packageName, param.classLoader,
            param.applicationInfo.uid, param.applicationInfo.flags)
    }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
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
                    putLong("peer_sequence", ProxyCommand.publicationSequence())
                    putString("process", "system_server")
                    putString("package", "android")
                    putInt("uid", 1000)
                    putInt("flags", 0)
                }
                // This API transfers objects within this process; it does not parcel them.
                // Share the drained platform monitor/counters so queued old interceptors see
                // the successor generation. Copying them into a Bundle splits the gate.
                param.setSavedInstanceState(arrayOf(state, gate.transferState()))
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
            lifecycle.systemServerStarting(loader)
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
        rows("raw").forEach { if (RawGnssCallbacks.restore(it)) RawGnssCallbacks.installRestored(it.listener) }
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

/** Keeps lifecycle ordering and package idempotence independent of Android installation details. */internal class ModuleInstallScheduler(
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
