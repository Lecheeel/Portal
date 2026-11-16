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
                    val target = callback.thisObject as? IInterface
                    val entry = target?.asBinder()?.let { requests[it] }
                    try {
                        // The original request has already performed framework permission checks.
                        // Only timeout / confirmed no-radio modem errors can become modeled data.
                        val noRadio = canModelError(callback.args)
                        if (entry != null && noRadio) {
                            val profile = CarrierIdentityRuntime.profileForCall("getAllCellInfo",
                                listOfNotNull(entry.subscription), entry.uid)
                            if (profile?.virtual == true) {
                                val result = runCatching {
                                    CellInfoSynthesis.resultForCaller(null, entry.uid, entry.subscription)
                                }.getOrNull()
                                if (result is CellInfoSynthesisResult.Synthetic) {
                                    target.javaClass.getMethod("onCellInfo", List::class.java)
                                        .invoke(target, result.cells)
                                    return@Hooker null
                                }
                            }
                        }
                        callback.proceed()
                    } finally { target?.asBinder()?.let(::remove) }
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

    internal fun canModelError(args: List<Any?>): Boolean {
        if (args.firstOrNull() == android.telephony.TelephonyManager.CellInfoCallback.ERROR_TIMEOUT) return true
        if (args.firstOrNull() != android.telephony.TelephonyManager.CellInfoCallback.ERROR_MODEM_ERROR) return false
        val detail = args.filterIsInstance<String>().joinToString(" ")
        return detail.contains("CommandException") &&
            (detail.contains("RADIO_NOT_AVAILABLE") || detail.contains("NO_NETWORK_FOUND"))
    }

    private fun remove(binder: IBinder) {
        requests.remove(binder)?.let { runCatching { binder.unlinkToDeath(it.death, 0) } }
    }
}
