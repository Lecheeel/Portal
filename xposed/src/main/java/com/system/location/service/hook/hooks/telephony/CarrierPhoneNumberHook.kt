package com.system.location.service.hook.hooks.telephony

import com.system.location.service.hook.runtime.Reflection
import com.system.location.service.hook.utils.hookAllMethods
import io.github.libxposed.api.XposedInterface.Hooker

/** Covers both legacy line-1 and Android 13+ SubscriptionManager phone number sources. */
internal object CarrierPhoneNumberHook {
    fun install(loader: ClassLoader) {
        val hosts = mapOf(
            "com.android.internal.telephony.PhoneSubInfoController" to listOf("getLine1NumberForSubscriber", "getLine1Number"),
            "com.android.phone.PhoneInterfaceManager" to listOf("getLine1NumberForDisplay"),
            "com.android.internal.telephony.SubscriptionController" to listOf("getPhoneNumber", "getPhoneNumberFromFirstAvailableSource"),
            "com.android.internal.telephony.subscription.SubscriptionManagerService" to listOf("getPhoneNumber", "getPhoneNumberFromFirstAvailableSource"),
        )
        hosts.forEach { (name, methods) ->
            val host = Reflection.findClassIfExists(name, loader) ?: return@forEach
            methods.forEach { method -> host.hookAllMethods(method, Hooker { chain ->
                val original = chain.proceed() // retain permission, attribution, invalid-source exceptions
                if (!CarrierPhoneNumberAccess.permitted(chain.args.filterIsInstance<String>().firstOrNull(),
                    legacy = chain.executable.name.startsWith("getLine1Number")))
                    return@Hooker original
                // SOURCE_UICC=1; no IMS/carrier record is invented for other explicit sources.
                if (chain.executable.name == "getPhoneNumber" && chain.args.getOrNull(1) is Int && chain.args[1] != 1)
                    return@Hooker original
                val profile = CarrierIdentityRuntime.profileForCall("getActiveSubscriptionInfo", chain.args)
                    ?: return@Hooker original
                profile.phoneNumber.takeIf { it.isNotEmpty() } ?: original
            }) }
        }
    }
}
