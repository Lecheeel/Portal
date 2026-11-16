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
                val subId = chain.args.firstOrNull() as? Int ?: return@Hooker original
                forCaller(Binder.getCallingUid(), subId) ?: original
            })
        }
    }

    internal fun forCaller(uid: Int, subId: Int): SignalStrength? {
        val profile = CarrierIdentityRuntime.profileForCall("getSignalStrength", listOf(subId), uid) ?: return null
        if (!profile.virtual || profile.radio != com.system.location.service.core.carrier.CarrierRadio.NR) return null
        val result = CellInfoSynthesis.resultForCaller(null, uid, subId) as? CellInfoSynthesisResult.Synthetic
            ?: return null
        val serving = result.cells.filterIsInstance<CellInfoNr>().firstOrNull { it.isRegistered } ?: return null
        return aggregate(serving.cellSignalStrength as CellSignalStrengthNr)
    }

    internal fun aggregate(serving: CellSignalStrengthNr): SignalStrength =
        (Reflection.newInstance(SignalStrength::class.java) as SignalStrength).apply {
        // Default platform object initializes other RATs to unavailable. Keep them that way for SA,
        // rather than carrying absent-modem GSM/LTE state into the virtual NR subscriber.
        val copy = Reflection.newInstance(CellSignalStrengthNr::class.java, serving) as CellSignalStrengthNr
        Reflection.setObjectField(this, "mNr", copy)
    }
}
