package com.system.location.service.hook.hooks.telephony

import android.telephony.ServiceState
import com.system.location.service.core.carrier.SimCarrierProfile
import com.system.location.service.hook.runtime.Reflection
import com.system.location.service.hook.utils.hookAllMethods
import io.github.libxposed.api.XposedInterface.Hooker

/** Keep the original registration, emergency, roaming and transport state in a caller-local copy. */
object CarrierServiceStateHook {
    fun install(loader: ClassLoader) {
        val type = Reflection.findClassIfExists("com.android.phone.PhoneInterfaceManager", loader) ?: return
        val hook = Hooker { chain ->
            val original = chain.proceed()
            val state = original as? ServiceState ?: return@Hooker original
            val profile = CarrierIdentityRuntime.profileForCall(chain.executable.name, chain.args)
                ?: return@Hooker original
            copyWithIdentity(state, profile)
        }
        listOf("getServiceStateForSubscriber", "getServiceState").forEach { type.hookAllMethods(it, hook) }
    }

    fun copyWithIdentity(original: ServiceState, profile: SimCarrierProfile): ServiceState =
        ServiceState(original).apply {
            // Preserve null/empty fields used for unavailable or redacted operator information.
            // Identity rewriting must not turn an out-of-service phone into a registered one.
            setOperatorName(
                if (original.operatorAlphaLong.isNullOrEmpty()) original.operatorAlphaLong else profile.operatorName,
                if (original.operatorAlphaShort.isNullOrEmpty()) original.operatorAlphaShort else profile.carrierName,
                if (original.operatorNumeric.isNullOrEmpty()) original.operatorNumeric else profile.operatorNumeric,
            )
        }
}
