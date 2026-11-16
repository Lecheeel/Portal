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
            listener.javaClass.onceHookAllMethod("onServiceStateChanged", Hooker { callback ->
                val target = callback.thisObject as? IInterface ?: return@Hooker callback.proceed()
                val entry = listeners[target.asBinder()] ?: return@Hooker callback.proceed()
                val original = callback.args.firstOrNull() as? ServiceState ?: return@Hooker callback.proceed()
                val profile = CarrierIdentityRuntime.profileForCall("getServiceStateForSubscriber",
                    listOf(entry.subId), entry.uid) ?: return@Hooker callback.proceed()
                val args = callback.args.toTypedArray()
                args[0] = CarrierServiceStateHook.copyWithIdentity(original, profile)
                callback.proceed(args)
            })
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

    private fun remove(binder: IBinder) {
        listeners.remove(binder)?.let { runCatching { binder.unlinkToDeath(it.death, 0) } }
    }
}
