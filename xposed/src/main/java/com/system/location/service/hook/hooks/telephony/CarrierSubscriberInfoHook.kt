package com.system.location.service.hook.hooks.telephony

import com.system.location.service.core.carrier.CarrierVirtualIdentity
import com.system.location.service.hook.runtime.Reflection
import com.system.location.service.hook.utils.hookAllMethods
import io.github.libxposed.api.XposedInterface.Hooker

/** IPhoneSubInfo is hosted by PhoneSubInfoController, separately from ITelephony/PIM. */
internal object CarrierSubscriberInfoHook {
    fun install(loader: ClassLoader) {
        val service = Reflection.findClassIfExists("com.android.internal.telephony.PhoneSubInfoController", loader)
            ?: return
        val imsi = Hooker { chain ->
            val original = chain.proceed() // original attribution, permission and exception checks
            if (!CarrierIdentifierAccess.permitted()) return@Hooker original
            val profile = CarrierIdentityRuntime.profileForCall(chain.executable.name, chain.args)
            if (profile?.virtual == true) CarrierVirtualIdentity.imsi(profile) else original
        }
        listOf("getSubscriberIdForSubscriber", "getSubscriberId", "getSubscriberIdForPhone")
            .forEach { service.hookAllMethods(it, imsi) }
        val iccid = Hooker { chain ->
            val original = chain.proceed()
            if (!CarrierIdentifierAccess.permitted()) return@Hooker original
            val profile = CarrierIdentityRuntime.profileForCall(chain.executable.name, chain.args)
            if (profile?.virtual == true) CarrierVirtualIdentity.iccid(profile) else original
        }
        listOf("getIccSerialNumberForSubscriber", "getIccSerialNumber", "getIccIdForSubscriber")
            .forEach { service.hookAllMethods(it, iccid) }
    }
}
