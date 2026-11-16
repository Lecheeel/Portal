package com.system.location.service.hook.hooks.telephony

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import com.system.location.service.hook.scope.HookInstaller
import com.system.location.service.hook.utils.onceHookAllMethod
import io.github.libxposed.api.XposedInterface.Hooker
import java.util.concurrent.ConcurrentHashMap

/** Protects the one-shot ICellInfoCallback transport in the phone process, before registration. */
internal object CarrierCellRequestHook {
    private data class Entry(val uid: Int, val subscription: Int?, val death: IBinder.DeathRecipient)
    private val requests = ConcurrentHashMap<IBinder, Entry>()

    fun install(manager: Class<*>) {
        listOf("requestCellInfoUpdate", "requestCellInfoUpdateWithWorkSource").forEach { name ->
            HookInstaller.hookAllMethods(manager, name, Hooker { chain ->
                val listener = chain.args.filterIsInstance<IInterface>().firstOrNull { candidate ->
                    candidate.javaClass.methods.any { it.name == "onCellInfo" }
                } ?: return@Hooker chain.proceed()
                val binder = listener.asBinder()
                listener.javaClass.onceHookAllMethod("onCellInfo", Hooker { callback ->
                    val target = callback.thisObject as? IInterface ?: return@Hooker callback.proceed()
                    val entry = requests[target.asBinder()] ?: return@Hooker callback.proceed()
                    try {
                        val profile = CarrierIdentityRuntime.profileForCall("getAllCellInfo",
                            listOfNotNull(entry.subscription), entry.uid)
                        if (profile == null && !CarrierIdentityRuntime.protectsLocation(entry.uid)) {
                            return@Hooker callback.proceed()
                        }
                        val result = runCatching {
                            CellInfoSynthesis.resultForCaller(null, entry.uid, entry.subscription)
                        }.getOrDefault(CellInfoSynthesisResult.Unavailable)
                        val cells = when (result) {
                            CellInfoSynthesisResult.PassThrough -> return@Hooker callback.proceed()
                            CellInfoSynthesisResult.Unavailable -> emptyList<android.telephony.CellInfo>()
                            is CellInfoSynthesisResult.Synthetic -> result.cells
                        }
                        val args = callback.args.toTypedArray()
                        if (args.isEmpty()) return@Hooker null
                        args[0] = cells
                        callback.proceed(args)
                    } finally { remove(target.asBinder()) }
                })
                listener.javaClass.onceHookAllMethod("onError", Hooker { callback ->
                    try { callback.proceed() } finally {
                        (callback.thisObject as? IInterface)?.asBinder()?.let(::remove)
                    }
                })
                val previous = requests[binder]
                val death = previous?.death ?: IBinder.DeathRecipient { remove(binder) }
                if (previous == null) binder.linkToDeath(death, 0)
                requests[binder] = Entry(Binder.getCallingUid(), chain.args.firstOrNull() as? Int, death)
                try { chain.proceed() } catch (failure: Throwable) {
                    if (previous == null) remove(binder) else requests[binder] = previous
                    throw failure
                }
            })
        }
    }

    private fun remove(binder: IBinder) {
        requests.remove(binder)?.let { runCatching { binder.unlinkToDeath(it.death, 0) } }
    }
}
