package com.system.location.service.hook.hooks.telephony

import android.telephony.TelephonyManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import com.system.location.service.hook.runtime.Reflection
import com.system.location.service.hook.scope.HookInstaller
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger
import com.system.location.service.hook.utils.onceHook
import io.github.libxposed.api.XposedInterface.Hooker
import java.util.concurrent.ConcurrentHashMap

/** Replaces requestCellInfoUpdate results in the requesting app process. */
object CellInfoAsyncCallbackHook {
    private val reportedUnavailable = ConcurrentHashMap.newKeySet<String>()

    fun install(classLoader: ClassLoader, packageName: String, uid: Int) {
        val telephonyManager = Reflection.findClassIfExists("android.telephony.TelephonyManager", classLoader)
            ?: return
        HookInstaller.hookAllMethods(telephonyManager, "requestCellInfoUpdate", Hooker { chain ->
            val callbackIndex = chain.executable.parameterTypes.indexOfFirst {
                TelephonyManager.CellInfoCallback::class.java.isAssignableFrom(it)
            }
            val callback = chain.args.getOrNull(callbackIndex) as? TelephonyManager.CellInfoCallback
                ?: return@Hooker chain.proceed()
            hookCallback(callback, packageName, uid)
            chain.proceed()
        })
        HookInstaller.hookAllMethods(telephonyManager, "listen", Hooker { chain ->
            val listenerIndex = chain.executable.parameterTypes.indexOfFirst {
                PhoneStateListener::class.java.isAssignableFrom(it)
            }
            val listener = chain.args.getOrNull(listenerIndex) as? PhoneStateListener
                ?: return@Hooker chain.proceed()
            hookListCallback(listener, "onCellInfoChanged", packageName, uid) {
                reportUnavailable(packageName, "listen")
                null
            }
            chain.proceed()
        })
        HookInstaller.hookAllMethods(telephonyManager, "registerTelephonyCallback", Hooker { chain ->
            val callbackIndex = chain.executable.parameterTypes.indexOfFirst {
                TelephonyCallback::class.java.isAssignableFrom(it)
            }
            val callback = chain.args.getOrNull(callbackIndex) as? TelephonyCallback
                ?: return@Hooker chain.proceed()
            hookListCallback(callback, "onCellInfoChanged", packageName, uid) {
                reportUnavailable(packageName, "registerTelephonyCallback")
                null
            }
            chain.proceed()
        })
    }

    private fun hookCallback(callback: TelephonyManager.CellInfoCallback, packageName: String, uid: Int) {
        hookListCallback(callback, "onCellInfo", packageName, uid) { target ->
            reportUnavailable(packageName, "requestCellInfoUpdate")
            runCatching {
                Reflection.callMethod(
                    target,
                    "onError",
                    TelephonyManager.CellInfoCallback.ERROR_MODEM_ERROR,
                    UnsupportedOperationException("Synthetic cell information is unavailable"),
                )
            }
            null
        }
    }

    private fun hookListCallback(
        callback: Any,
        methodName: String,
        packageName: String,
        uid: Int,
        unavailable: (Any) -> Any?,
    ) {
        val method = Reflection.findMethodExactIfExists(callback.javaClass, methodName, List::class.java)
            ?: return
        method.onceHook(Hooker { chain ->
            if (!FakeLoc.enable) return@Hooker chain.proceed()
            when (val result = CellInfoSynthesis.resultForCaller(packageName, uid)) {
                CellInfoSynthesisResult.PassThrough -> chain.proceed()
                CellInfoSynthesisResult.Unavailable -> unavailable(chain.thisObject)
                is CellInfoSynthesisResult.Synthetic -> dispatchCellInfoCallback(
                    protectedCall = true,
                    synthetic = result.cells,
                    passThrough = { chain.proceed() },
                    deliver = { cells ->
                        val args = chain.args.toTypedArray()
                        args[0] = cells
                        chain.proceed(args)
                    },
                    unavailable = { unavailable(chain.thisObject) },
                )
            }
        })
    }

    private fun reportUnavailable(packageName: String, entryPoint: String) {
        if (reportedUnavailable.add("$packageName:$entryPoint")) {
            Logger.warn("$entryPoint: cell simulation unavailable for $packageName; real cell update suppressed")
        }
    }
}

internal sealed interface CellInfoCallbackDecision<out T> {
    data object PassThrough : CellInfoCallbackDecision<Nothing>
    data class Deliver<T>(val cells: List<T>) : CellInfoCallbackDecision<T>
    data object Unsupported : CellInfoCallbackDecision<Nothing>
}

internal fun <T> cellInfoCallbackDecision(
    protectedCall: Boolean,
    synthetic: List<T>,
): CellInfoCallbackDecision<T> = when {
    !protectedCall -> CellInfoCallbackDecision.PassThrough
    synthetic.isEmpty() -> CellInfoCallbackDecision.Unsupported
    else -> CellInfoCallbackDecision.Deliver(synthetic)
}

internal fun <T, R> dispatchCellInfoCallback(
    protectedCall: Boolean,
    synthetic: List<T>,
    passThrough: () -> R,
    deliver: (List<T>) -> R,
    unavailable: () -> R,
): R = when (val decision = cellInfoCallbackDecision(protectedCall, synthetic)) {
    CellInfoCallbackDecision.PassThrough -> passThrough()
    is CellInfoCallbackDecision.Deliver -> deliver(decision.cells)
    CellInfoCallbackDecision.Unsupported -> unavailable()
}
