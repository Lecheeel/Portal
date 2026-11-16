package com.system.location.service.hook.hooks.telephony

import android.os.Binder
import android.os.Parcel
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import com.system.location.service.core.carrier.CarrierVirtualIdentity
import com.system.location.service.core.carrier.SimCarrierProfile
import com.system.location.service.hook.runtime.Reflection
import com.system.location.service.hook.utils.Logger
import com.system.location.service.hook.utils.hookAllMethods
import io.github.libxposed.api.XposedInterface.Hooker

/** Preserves real IDs while adding a bounded virtual subscription only when the caller may see it. */
internal object CarrierSubscriptionHook {
    fun install(loader: ClassLoader) {
        listOf("com.android.internal.telephony.SubscriptionController",
            "com.android.internal.telephony.subscription.SubscriptionManagerService").forEach { name ->
            val type = Reflection.findClassIfExists(name, loader) ?: return@forEach
            val hook = Hooker { chain ->
                if (CarrierIdentityRuntime.isResolving()) return@Hooker chain.proceed()
                val original = chain.proceed()
                val virtuals = CarrierIdentityRuntime.virtualProfilesForCall(Binder.getCallingUid())
                when (chain.executable.name) {
                    "getActiveSubscriptionInfoList", "getAccessibleSubscriptionInfoList",
                    "getAvailableSubscriptionInfoList", "getSelectableSubscriptionInfoList" ->
                        mergeList(original, virtuals)
                    "getActiveSubscriptionInfo" -> {
                        val id = chain.args.firstOrNull() as? Int
                        virtualForSubId(virtuals, id) ?: overlay(original)
                    }
                    "getActiveSubscriptionInfoForIccId" -> {
                        val iccId = chain.args.firstOrNull() as? String
                        virtuals.firstOrNull { CarrierVirtualIdentity.iccid(it) == iccId }
                            ?.let(CarrierVirtualSubscription::info) ?: overlay(original)
                    }
                    "getActiveSubscriptionInfoForSimSlotIndex" -> {
                        val slot = (chain.args.firstOrNull() as? Int)?.plus(1)
                        virtuals.firstOrNull { it.slot == slot }
                            ?.let(CarrierVirtualSubscription::info) ?: overlay(original)
                    }
                    "getActiveSubscriptionInfoCount", "getActiveSubscriptionInfoCountMax" -> {
                        val physical = (original as? Int) ?: 0
                        (physical + virtuals.size).coerceAtMost(2)
                    }
                    "getDefaultSubId", "getDefaultDataSubId", "getDefaultVoiceSubId",
                    "getDefaultSmsSubId" -> {
                        val current = (original as? Int) ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
                        if (SubscriptionManager.isValidSubscriptionId(current)) current
                        else virtuals.firstOrNull()?.let { CarrierVirtualIdentity.subscriptionId(it.slot) }
                            ?: current
                    }
                    else -> overlay(original)
                }
            }
            listOf("getActiveSubscriptionInfo", "getActiveSubscriptionInfoForIccId",
                "getActiveSubscriptionInfoForSimSlotIndex", "getActiveSubscriptionInfoList",
                "getAccessibleSubscriptionInfoList", "getAvailableSubscriptionInfoList",
                "getSelectableSubscriptionInfoList", "getActiveSubscriptionInfoCount",
                "getActiveSubscriptionInfoCountMax", "getDefaultSubId", "getDefaultDataSubId",
                "getDefaultVoiceSubId", "getDefaultSmsSubId")
                .forEach { method -> type.hookAllMethods(method, hook) }
        }
    }

    private fun mergeList(original: Any?, virtuals: List<SimCarrierProfile>): List<SubscriptionInfo> {
        val physical = (original as? List<*>).orEmpty().mapNotNull { it as? SubscriptionInfo }
            .map(::overlay)
        val physicalSlots = physical.map { it.simSlotIndex + 1 }.toSet()
        val extra = virtuals.filter { it.slot !in physicalSlots }.mapNotNull(CarrierVirtualSubscription::info)
        return physical + extra
    }

    private fun virtualForSubId(
        virtuals: List<SimCarrierProfile>,
        subId: Int?,
    ): SubscriptionInfo? {
        val slot = subId?.let(CarrierVirtualIdentity::slotForSubscriptionId) ?: return null
        return virtuals.firstOrNull { it.slot == slot }?.let(CarrierVirtualSubscription::info)
    }

    private fun overlay(original: Any?): Any? = when (original) {
        is SubscriptionInfo -> overlay(original)
        is List<*> -> original.map { item -> if (item is SubscriptionInfo) overlay(item) else item }
        else -> original
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
