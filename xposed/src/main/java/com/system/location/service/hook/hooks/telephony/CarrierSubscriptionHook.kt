package com.system.location.service.hook.hooks.telephony

import android.os.Parcel
import android.telephony.SubscriptionInfo
import com.system.location.service.core.carrier.SimCarrierProfile
import com.system.location.service.hook.runtime.Reflection
import com.system.location.service.hook.utils.Logger
import com.system.location.service.hook.utils.hookAllMethods
import io.github.libxposed.api.XposedInterface.Hooker

/** Preserves subscription IDs, slot topology and permission redactions in a per-caller copy. */
internal object CarrierSubscriptionHook {
    fun install(loader: ClassLoader) {
        listOf("com.android.internal.telephony.SubscriptionController",
            "com.android.internal.telephony.subscription.SubscriptionManagerService").forEach { name ->
            val type = Reflection.findClassIfExists(name, loader) ?: return@forEach
            val hook = Hooker { chain ->
                val original = chain.proceed()
                when (original) {
                    is SubscriptionInfo -> overlay(original)
                    is List<*> -> original.map { item -> if (item is SubscriptionInfo) overlay(item) else item }
                    else -> original
                }
            }
            listOf("getActiveSubscriptionInfo", "getActiveSubscriptionInfoForIccId",
                "getActiveSubscriptionInfoForSimSlotIndex", "getActiveSubscriptionInfoList")
                .forEach { method -> type.hookAllMethods(method, hook) }
        }
    }

    private fun overlay(info: SubscriptionInfo): SubscriptionInfo {
        val profile = CarrierIdentityRuntime.profileForCall("getActiveSubscriptionInfo",
            listOf(info.subscriptionId)) ?: return info
        return runCatching { copyWithIdentity(info, profile) }.getOrElse {
            Logger.warn("Subscription identity overlay unavailable: ${it.javaClass.simpleName}")
            info
        }
    }

    internal fun copyWithIdentity(info: SubscriptionInfo, profile: SimCarrierProfile): SubscriptionInfo {
        val parcel = Parcel.obtain()
        val copy = try {
            info.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            SubscriptionInfo.CREATOR.createFromParcel(parcel)
        } finally { parcel.recycle() }
        Reflection.setObjectField(copy, "mCarrierName", profile.carrierName)
        Reflection.setObjectField(copy, "mDisplayName", profile.operatorName)
        Reflection.setObjectField(copy, "mCountryIso", profile.countryIso.lowercase(java.util.Locale.ROOT))
        Reflection.setObjectField(copy, "mMcc", profile.mcc.toString().padStart(3, '0'))
        Reflection.setObjectField(copy, "mMnc", profile.mnc.toString().padStart(profile.mncDigits, '0'))
        return copy
    }
}
