package com.system.location.service.hook.hooks.telephony

import android.os.Binder
import android.telephony.CellInfoNr
import android.telephony.CellSignalStrengthNr
import android.telephony.SignalStrength
import com.system.location.service.hook.runtime.Reflection
import com.system.location.service.hook.utils.hookAllMethods
import io.github.libxposed.api.XposedInterface.Hooker

/** NR SA aggregate signal uses the same serving record as per-subscription CellInfo. */
internal object CarrierSignalStrengthHook {
    fun install(loader: ClassLoader) {
        val manager = Reflection.findClassIfExists("com.android.phone.PhoneInterfaceManager", loader) ?: return
        listOf("getSignalStrength", "getSignalStrengthForSubscriber").forEach { name ->
            manager.hookAllMethods(name, Hooker { chain ->
                val original = chain.proceed() // retain permission checks and platform exceptions
                val uid = Binder.getCallingUid()
                val profile = CarrierIdentityRuntime.profileForCall(chain.executable.name, chain.args, uid)
                    ?: return@Hooker original
                if (!profile.virtual || profile.radio != com.system.location.service.core.carrier.CarrierRadio.NR)
                    return@Hooker original
                val subId = chain.args.firstOrNull() as? Int ?: return@Hooker original
                val cells = CellInfoSynthesis.resultForCaller(null, uid, subId)
                val serving = (cells as? CellInfoSynthesisResult.Synthetic)?.cells
                    ?.filterIsInstance<CellInfoNr>()?.firstOrNull { it.isRegistered } ?: return@Hooker original
                aggregate(serving.cellSignalStrength as CellSignalStrengthNr)
            })
        }
    }

    internal fun aggregate(serving: CellSignalStrengthNr): SignalStrength =
        (Reflection.newInstance(SignalStrength::class.java) as SignalStrength).apply {
        // Default platform object initializes other RATs to unavailable. Keep them that way for SA,
        // rather than carrying absent-modem GSM/LTE state into the virtual NR subscriber.
        val copy = Reflection.newInstance(CellSignalStrengthNr::class.java, serving) as CellSignalStrengthNr
        Reflection.setObjectField(this, "mNr", copy)
    }
}
