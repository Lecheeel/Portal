package com.system.location.service.hook.hooks.telephony

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.telephony.ServiceState
import com.system.location.service.hook.scope.HookInstaller
import com.system.location.service.hook.utils.onceHookAllMethod
import io.github.libxposed.api.XposedInterface.Hooker
import java.util.concurrent.ConcurrentHashMap

/** Binds callback transports to the original caller and requested subscription before notifyNow. */
internal object CarrierRegistryHook {
    private const val EVENT_SERVICE_STATE_CHANGED = 1
    private const val EVENT_CELL_INFO_CHANGED = 11
    private const val EVENT_SIGNAL_STRENGTHS_CHANGED = 9
    private const val EVENT_DISPLAY_INFO_CHANGED = 21
    private class Entry(val uid: Int, val subId: Int, val death: IBinder.DeathRecipient,
        val serviceState: Boolean, val cellInfo: Boolean, val signalStrength: Boolean, val displayInfo: Boolean) {
        @Volatile var displaySent = false
        @Volatile var lastDisplay: android.telephony.TelephonyDisplayInfo? = null
        @Volatile var realDisplay: android.telephony.TelephonyDisplayInfo? = null
        @Volatile var signalSent = false
        @Volatile var lastSignal: android.telephony.SignalStrength? = null
        val movementGate = com.system.location.service.core.carrier.CarrierCellPublicationGate()
        @Volatile var cellsSent = false
        @Volatile var lastRealState: ServiceState? = null
        @Volatile var virtualSent = false
    }
    private val dispatching = ThreadLocal.withInitial { false }
    private val updates = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PortalCarrierCallbacks").apply { isDaemon = true }
    }
    private val listeners = ConcurrentHashMap<IBinder, Entry>()

    fun install(registry: Class<*>) {
        registry.declaredMethods.filter { method ->
            method.name == "getServiceStateForSubscriber" &&
                method.returnType == ServiceState::class.java
        }.forEach { method ->
            HookInstaller.hookMethod(method, Hooker { chain ->
                val original = chain.proceed()
                val subId = chain.args.firstOrNull() as? Int
                if (subId == null) return@Hooker original
                val profile = CarrierIdentityRuntime.profileForCall(
                    "getServiceStateForSubscriber", listOf(subId), Binder.getCallingUid())
                if (profile?.virtual == true) CarrierServiceStateHook.virtualState(profile)
                else original
            })
        }
        HookInstaller.hookAllMethods(registry, "listenWithEventList", Hooker { chain ->
            val listener = chain.args.filterIsInstance<IInterface>().firstOrNull {
                it.javaClass.methods.any { method -> method.name == "onServiceStateChanged" }
            } ?: return@Hooker chain.proceed()
            val subId = chain.args.filterIsInstance<Int>().firstOrNull() ?: return@Hooker chain.proceed()
            val events = chain.args.filterIsInstance<IntArray>().firstOrNull() ?: return@Hooker chain.proceed()
            val binder = listener.asBinder()
            if (events.isEmpty()) {
                val result = chain.proceed()
                remove(binder)
                return@Hooker result
            }
            installCallback(listener)
            // Registry may send its cached state synchronously while registering. Install before
            // proceeding, but roll back if framework permission validation throws.
            val previous = listeners[binder]
            val death = previous?.death ?: IBinder.DeathRecipient { remove(binder) }
            if (previous == null) binder.linkToDeath(death, 0)
            listeners[binder] = Entry(Binder.getCallingUid(), subId, death,
                EVENT_SERVICE_STATE_CHANGED in events, EVENT_CELL_INFO_CHANGED in events,
                EVENT_SIGNAL_STRENGTHS_CHANGED in events, EVENT_DISPLAY_INFO_CHANGED in events).apply {
                displaySent = previous?.displaySent ?: false
                lastDisplay = previous?.lastDisplay
                realDisplay = previous?.realDisplay
                signalSent = previous?.signalSent ?: false
                lastSignal = previous?.lastSignal
                cellsSent = previous?.cellsSent ?: false
                lastRealState = previous?.lastRealState
                virtualSent = previous?.virtualSent ?: false
            }
            try {
                val result = chain.proceed()
                if (!CarrierRegistrationPolicy.notifyNow(chain.args)) return@Hooker result
                // A no-SIM virtual subscription has no modem registration to generate the first
                // ServiceState callback. Seed the listener so SystemUI can render the carrier.
                val callerUid = listeners[binder]?.uid ?: -1
                val profile = CarrierIdentityRuntime.profileForCall(
                    "getServiceStateForSubscriber", listOf(subId), callerUid,
                )
                if (profile?.virtual == true && EVENT_SERVICE_STATE_CHANGED in events) {
                    val synthetic = CarrierServiceStateHook.virtualState(profile)
                    runCatching {
                        val method = listener.javaClass.methods.firstOrNull { method ->
                            method.name == "onServiceStateChanged" &&
                                method.parameterTypes.toList() == listOf(ServiceState::class.java)
                        } ?: error("onServiceStateChanged proxy method missing")
                        val entry = listeners[binder] ?: return@runCatching
                        dispatching.set(true)
                        try { method.invoke(listener, synthetic); entry.virtualSent = true }
                        finally { dispatching.set(false) }
                    }.onFailure {
                        com.system.location.service.hook.utils.Logger.warn(
                            "virtual ServiceState seed failed: ${it.javaClass.simpleName}:${it.message}")
                    }
                }
                if (profile?.virtual == true && EVENT_CELL_INFO_CHANGED in events) {
                    listeners[binder]?.let { updateCells(binder, it) }
                }
                if (profile?.virtual == true && EVENT_SIGNAL_STRENGTHS_CHANGED in events) {
                    listeners[binder]?.let { updateSignal(binder, it) }
                }
                if (profile?.virtual == true && EVENT_DISPLAY_INFO_CHANGED in events) {
                    listeners[binder]?.let { updateDisplay(binder, it) }
                }
                result
            } catch (failure: Throwable) {
                if (previous == null) remove(binder) else listeners[binder] = previous
                throw failure
            }
        })
    }

    private fun installCallback(listener: IInterface) {
        listener.javaClass.onceHookAllMethod("onDisplayInfoChanged", Hooker { callback ->
            if (dispatching.get() == true) return@Hooker callback.proceed()
            val target = callback.thisObject as? IInterface ?: return@Hooker callback.proceed()
            val entry = listeners[target.asBinder()] ?: return@Hooker callback.proceed()
            entry.realDisplay = callback.args.firstOrNull() as? android.telephony.TelephonyDisplayInfo
            val profile = CarrierIdentityRuntime.profileForCall("getServiceStateForSubscriber", listOf(entry.subId), entry.uid)
            if (profile?.virtual != true) {
                entry.displaySent = false
                return@Hooker callback.proceed()
            }
            val display = CarrierDisplayInfo.create(profile)
            val args = callback.args.toTypedArray()
            if (args.isEmpty()) return@Hooker callback.proceed()
            args[0] = display
            val result = callback.proceed(args)
            entry.displaySent = true
            entry.lastDisplay = display
            result
        })
        listener.javaClass.onceHookAllMethod("onSignalStrengthsChanged", Hooker { callback ->
            if (dispatching.get() == true) return@Hooker callback.proceed()
            val target = callback.thisObject as? IInterface ?: return@Hooker callback.proceed()
            val entry = listeners[target.asBinder()] ?: return@Hooker callback.proceed()
            val signal = CarrierSignalStrengthHook.forCaller(entry.uid, entry.subId) ?: return@Hooker callback.proceed()
            val args = callback.args.toTypedArray()
            if (args.isEmpty()) return@Hooker callback.proceed()
            args[0] = signal
            val result = callback.proceed(args)
            entry.signalSent = true
            entry.lastSignal = signal
            result
        })
        listener.javaClass.onceHookAllMethod("onServiceStateChanged", Hooker { callback ->
            if (dispatching.get() == true) return@Hooker callback.proceed()
            val target = callback.thisObject as? IInterface ?: return@Hooker callback.proceed()
            val entry = listeners[target.asBinder()] ?: return@Hooker callback.proceed()
            val original = callback.args.firstOrNull() as? ServiceState ?: return@Hooker callback.proceed()
            entry.lastRealState = CarrierServiceStateHook.copyWithoutCellIdentity(original)
            val profile = CarrierIdentityRuntime.profileForCall("getServiceStateForSubscriber",
                listOf(entry.subId), entry.uid)
            val args = callback.args.toTypedArray()
            args[0] = when {
                profile != null -> CarrierServiceStateHook.copyWithIdentity(original, profile)
                CarrierIdentityRuntime.protectsLocation(entry.uid) -> CarrierServiceStateHook.copyWithoutCellIdentity(original)
                else -> return@Hooker callback.proceed()
            }
            val result = callback.proceed(args)
            entry.virtualSent = profile?.virtual == true
            result
        })
        listener.javaClass.onceHookAllMethod("onCellInfoChanged", Hooker { callback ->
            if (dispatching.get() == true) return@Hooker callback.proceed()
            val target = callback.thisObject as? IInterface ?: return@Hooker callback.proceed()
            val entry = listeners[target.asBinder()] ?: return@Hooker callback.proceed()
            val identity = CarrierIdentityRuntime.profileForCall("getAllCellInfo", listOf(entry.subId), entry.uid)
            if (identity == null && !CarrierIdentityRuntime.protectsLocation(entry.uid)) return@Hooker callback.proceed()
            val result = runCatching { CellInfoSynthesis.resultForCaller(null, entry.uid, entry.subId) }
                .getOrDefault(CellInfoSynthesisResult.Unavailable)
            val cells = when (result) {
                CellInfoSynthesisResult.PassThrough -> return@Hooker callback.proceed()
                CellInfoSynthesisResult.Unavailable -> emptyList<android.telephony.CellInfo>()
                is CellInfoSynthesisResult.Synthetic -> result.cells
            }
            val args = callback.args.toTypedArray()
            if (args.isEmpty()) return@Hooker null
            args[0] = cells
            val delivered = callback.proceed(args)
            entry.cellsSent = identity?.virtual == true && cells.isNotEmpty()
            delivered
        })
    }

    private fun updateDisplay(binder: IBinder, entry: Entry) {
        if (!entry.displayInfo || listeners[binder] !== entry || !binder.isBinderAlive) return
        runCatching {
            val profile = CarrierIdentityRuntime.profileForCall("getServiceStateForSubscriber", listOf(entry.subId), entry.uid)
            val virtual = profile?.virtual == true
            val display = if (virtual) CarrierDisplayInfo.create(profile!!) else if (entry.displaySent)
                entry.realDisplay ?: CarrierDisplayInfo.unavailable() else return@runCatching
            if (virtual && entry.displaySent && display == entry.lastDisplay) return@runCatching
            val listener = Class.forName("com.android.internal.telephony.IPhoneStateListener\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder) as IInterface
            if (listeners[binder] !== entry) return@runCatching
            dispatching.set(true)
            try {
                listener.javaClass.getMethod("onDisplayInfoChanged", android.telephony.TelephonyDisplayInfo::class.java)
                    .invoke(listener, display)
                entry.displaySent = virtual
                entry.lastDisplay = if (virtual) display else null
            } finally { dispatching.set(false) }
        }.onFailure {
            com.system.location.service.hook.utils.Logger.warn("Carrier display update failed: ${it.javaClass.simpleName}")
        }
    }

    private fun updateSignal(binder: IBinder, entry: Entry) {
        if (!entry.signalStrength || listeners[binder] !== entry || !binder.isBinderAlive) return
        runCatching {
            val modeled = CarrierSignalStrengthHook.forCaller(entry.uid, entry.subId)
            if (modeled != null && entry.signalSent && modeled == entry.lastSignal) return@runCatching
            val signal = modeled ?: if (entry.signalSent)
                com.system.location.service.hook.runtime.Reflection.newInstance(android.telephony.SignalStrength::class.java)
                    as android.telephony.SignalStrength else return@runCatching
            val listener = Class.forName("com.android.internal.telephony.IPhoneStateListener\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder) as IInterface
            if (listeners[binder] !== entry) return@runCatching
            dispatching.set(true)
            try {
                listener.javaClass.getMethod("onSignalStrengthsChanged", android.telephony.SignalStrength::class.java)
                    .invoke(listener, signal)
                entry.signalSent = modeled != null
                entry.lastSignal = modeled
            } finally { dispatching.set(false) }
        }.onFailure {
            com.system.location.service.hook.utils.Logger.warn("Carrier signal update failed: ${it.javaClass.simpleName}")
        }
    }

    private fun updateCells(binder: IBinder, entry: Entry) {
        if (!entry.cellInfo || listeners[binder] !== entry || !binder.isBinderAlive) return
        runCatching {
            val profile = CarrierIdentityRuntime.profileForCall("getAllCellInfo", listOf(entry.subId), entry.uid)
            val cells = if (profile?.virtual == true) {
                when (val result = CellInfoSynthesis.resultForCaller(null, entry.uid, entry.subId)) {
                    is CellInfoSynthesisResult.Synthetic -> result.cells
                    else -> emptyList()
                }
            } else if (entry.cellsSent) emptyList() else return@runCatching
            val listener = Class.forName("com.android.internal.telephony.IPhoneStateListener\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder) as IInterface
            if (listeners[binder] !== entry) return@runCatching
            dispatching.set(true)
            try {
                listener.javaClass.getMethod("onCellInfoChanged", List::class.java).invoke(listener, cells)
                entry.cellsSent = profile?.virtual == true && cells.isNotEmpty()
            } finally { dispatching.set(false) }
        }.onFailure {
            com.system.location.service.hook.utils.Logger.warn("Carrier cell update failed: ${it.javaClass.simpleName}")
        }
    }

    private var lastMovementCheck = Long.MIN_VALUE
    private val movementPending = java.util.concurrent.atomic.AtomicBoolean()

    @Synchronized
    fun onSamplePublished() {
        if (updates.isShutdown || listeners.values.none { it.cellInfo || it.signalStrength }) return
        val state = com.system.location.service.hook.utils.FakeLoc.runtimeSnapshots.current()
        if (!state.configuration.enabled || !state.configuration.carrierIdentityEnabled) return
        if (movementPending.get()) return
        val now = android.os.SystemClock.elapsedRealtimeNanos()
        if (lastMovementCheck != Long.MIN_VALUE && now >= lastMovementCheck && now - lastMovementCheck < 1_500_000_000L) return
        if (!movementPending.compareAndSet(false, true)) return
        lastMovementCheck = now
        val generation = com.system.location.service.hook.runtime.GenerationDelivery(
            com.system.location.service.hook.runtime.HookRuntime.executionGate)
        try {
            updates.execute {
                try { generation.run {
                    val current = com.system.location.service.hook.utils.FakeLoc.runtimeSnapshots.current()
                    listeners.entries.toList().forEach { (binder, entry) ->
                        updateSignal(binder, entry)
                        if (!entry.cellInfo) return@forEach
                        runCatching {
                            val point = CellInfoSynthesis.pointForCaller(entry.uid, current) ?: return@runCatching
                            if (entry.movementGate.changed(point.latitude, point.longitude,
                                    android.os.SystemClock.elapsedRealtimeNanos())) updateCells(binder, entry)
                        }.onFailure {
                            com.system.location.service.hook.utils.Logger.warn("Carrier movement update failed: ${it.javaClass.simpleName}")
                        }
                    }
                } } finally { movementPending.set(false) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { movementPending.set(false) }
    }

    fun onConfigurationChanged() {
        if (listeners.isEmpty() || updates.isShutdown) return
        val generation = com.system.location.service.hook.runtime.GenerationDelivery(
            com.system.location.service.hook.runtime.HookRuntime.executionGate)
        runCatching { updates.execute { generation.run {
            listeners.entries.toList().forEach { (binder, entry) ->
                updateCells(binder, entry)
                updateSignal(binder, entry)
                updateDisplay(binder, entry)
                if (!entry.serviceState || listeners[binder] !== entry || !binder.isBinderAlive) return@forEach
                runCatching {
                    val profile = CarrierIdentityRuntime.profileForCall("getServiceStateForSubscriber",
                        listOf(entry.subId), entry.uid)
                    val state = when {
                        profile?.virtual == true -> CarrierServiceStateHook.virtualState(profile)
                        entry.virtualSent -> entry.lastRealState?.let(::ServiceState) ?: ServiceState()
                        else -> return@runCatching
                    }
                    val listener = Class.forName("com.android.internal.telephony.IPhoneStateListener\$Stub")
                        .getMethod("asInterface", IBinder::class.java).invoke(null, binder) as IInterface
                    val method = listener.javaClass.getMethod("onServiceStateChanged", ServiceState::class.java)
                    if (listeners[binder] !== entry) return@runCatching
                    dispatching.set(true)
                    try { method.invoke(listener, state); entry.virtualSent = profile?.virtual == true }
                    finally { dispatching.set(false) }
                }.onFailure {
                    com.system.location.service.hook.utils.Logger.warn("Carrier callback update failed: ${it.javaClass.simpleName}")
                }
            }
        } } }.onFailure {
            com.system.location.service.hook.utils.Logger.warn("Carrier callback scheduling failed: ${it.javaClass.simpleName}")
        }
    }

    fun saveReloadState(): ArrayList<android.os.Bundle> = ArrayList(listeners.entries.map { (binder, entry) ->
        android.os.Bundle().apply {
            putBinder("binder", binder)
            putInt("uid", entry.uid)
            putInt("subscription", entry.subId)
            putBoolean("service_state", entry.serviceState)
            putBoolean("cell_info", entry.cellInfo)
            putBoolean("signal_strength", entry.signalStrength)
            putBoolean("display_info", entry.displayInfo)
            putBoolean("display_sent", entry.displaySent)
            entry.lastDisplay?.let { putParcelable("last_display", it) }
            entry.realDisplay?.let { putParcelable("real_display", it) }
            putBoolean("signal_sent", entry.signalSent)
            entry.lastSignal?.let { putParcelable("last_signal", it) }
            putBoolean("cells_sent", entry.cellsSent)
            putBoolean("virtual_sent", entry.virtualSent)
            entry.lastRealState?.let { putParcelable("real_state", it) }
        }
    })

    fun retire() {
        updates.shutdownNow()
        listeners.keys.toList().forEach(::remove)
    }

    fun restoreReloadState(rows: List<android.os.Bundle>) {
        rows.forEach { row ->
            val binder = row.getBinder("binder") ?: return@forEach
            if (!binder.isBinderAlive) return@forEach
            try {
                val stub = Class.forName("com.android.internal.telephony.IPhoneStateListener\$Stub")
                val listener = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder) as IInterface
                installCallback(listener)
                val death = IBinder.DeathRecipient { remove(binder) }
                binder.linkToDeath(death, 0)
                listeners[binder] = Entry(row.getInt("uid"), row.getInt("subscription"), death,
                    row.getBoolean("service_state", false), row.getBoolean("cell_info", false),
                    row.getBoolean("signal_strength", false), row.getBoolean("display_info", false)).apply {
                    displaySent = row.getBoolean("display_sent", false)
                    @Suppress("DEPRECATION")
                    lastDisplay = row.getParcelable<android.telephony.TelephonyDisplayInfo>("last_display")
                    @Suppress("DEPRECATION")
                    realDisplay = row.getParcelable<android.telephony.TelephonyDisplayInfo>("real_display")
                    signalSent = row.getBoolean("signal_sent", false)
                    @Suppress("DEPRECATION")
                    lastSignal = row.getParcelable<android.telephony.SignalStrength>("last_signal")
                    cellsSent = row.getBoolean("cells_sent", false)
                    virtualSent = row.getBoolean("virtual_sent", false)
                    @Suppress("DEPRECATION")
                    lastRealState = row.getParcelable<ServiceState>("real_state")
                }
            } catch (failure: Exception) {
                com.system.location.service.hook.utils.Logger.warn("Carrier listener restore failed: ${failure.javaClass.simpleName}")
            }
        }
    }

    private fun remove(binder: IBinder) {
        listeners.remove(binder)?.let { runCatching { binder.unlinkToDeath(it.death, 0) } }
    }
}
