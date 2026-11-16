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
    private data class Entry(val uid: Int, val subId: Int, val death: IBinder.DeathRecipient)
    private val listeners = ConcurrentHashMap<IBinder, Entry>()

    fun install(registry: Class<*>) {
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
            listeners[binder] = Entry(Binder.getCallingUid(), subId, death)
            try { chain.proceed() } catch (failure: Throwable) {
                if (previous == null) remove(binder) else listeners[binder] = previous
                throw failure
            }
        })
    }

    private fun installCallback(listener: IInterface) {
        listener.javaClass.onceHookAllMethod("onServiceStateChanged", Hooker { callback ->
            val target = callback.thisObject as? IInterface ?: return@Hooker callback.proceed()
            val entry = listeners[target.asBinder()] ?: return@Hooker callback.proceed()
            val original = callback.args.firstOrNull() as? ServiceState ?: return@Hooker callback.proceed()
            val profile = CarrierIdentityRuntime.profileForCall("getServiceStateForSubscriber",
                listOf(entry.subId), entry.uid)
            val args = callback.args.toTypedArray()
            args[0] = when {
                profile != null -> CarrierServiceStateHook.copyWithIdentity(original, profile)
                CarrierIdentityRuntime.protectsLocation(entry.uid) -> CarrierServiceStateHook.copyWithoutCellIdentity(original)
                else -> return@Hooker callback.proceed()
            }
            callback.proceed(args)
        })
        listener.javaClass.onceHookAllMethod("onCellInfoChanged", Hooker { callback ->
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
            callback.proceed(args)
        })
    }

    fun saveReloadState(): ArrayList<android.os.Bundle> = ArrayList(listeners.entries.map { (binder, entry) ->
        android.os.Bundle().apply {
            putBinder("binder", binder)
            putInt("uid", entry.uid)
            putInt("subscription", entry.subId)
        }
    })

    fun retire() { listeners.keys.toList().forEach(::remove) }

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
                listeners[binder] = Entry(row.getInt("uid"), row.getInt("subscription"), death)
            } catch (failure: Exception) {
                com.system.location.service.hook.utils.Logger.warn("Carrier listener restore failed: ${failure.javaClass.simpleName}")
            }
        }
    }

    private fun remove(binder: IBinder) {
        listeners.remove(binder)?.let { runCatching { binder.unlinkToDeath(it.death, 0) } }
    }
}
