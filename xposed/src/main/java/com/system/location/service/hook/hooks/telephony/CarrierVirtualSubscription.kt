package com.system.location.service.hook.hooks.telephony

import android.telephony.SubscriptionInfo
import com.system.location.service.core.carrier.CarrierVirtualIdentity
import com.system.location.service.core.carrier.SimCarrierProfile
import com.system.location.service.hook.runtime.Reflection
import com.system.location.service.hook.utils.Logger

/** Builds the platform's opaque SubscriptionInfo object for a virtual slot. */
internal object CarrierVirtualSubscription {
    fun info(profile: SimCarrierProfile): SubscriptionInfo? = runCatching {
        val info = allocate()
        val subId = CarrierVirtualIdentity.subscriptionId(profile.slot)
        set(info, "mId", subId)
        set(info, "mSubscriptionId", subId)
        set(info, "mIccId", CarrierVirtualIdentity.iccid(profile))
        set(info, "mSimSlotIndex", profile.slot - 1)
        set(info, "mDisplayName", profile.operatorName)
        set(info, "mCarrierName", profile.carrierName)
        set(info, "mNameSource", 1)
        set(info, "mIconTint", 0)
        set(info, "mNumber", "")
        set(info, "mDataRoaming", 0)
        set(info, "mIcon", 0)
        set(info, "mMcc", profile.mcc.toString().padStart(3, '0'))
        set(info, "mMnc", profile.mnc.toString().padStart(profile.mncDigits, '0'))
        set(info, "mCountryIso", profile.countryIso.lowercase(java.util.Locale.ROOT))
        set(info, "mIsEmbedded", false)
        set(info, "mCardId", 0)
        set(info, "mCarrierId", CarrierVirtualIdentity.UNKNOWN_CARRIER_ID)
        set(info, "mProfileClass", 0)
        set(info, "mSubscriptionType", 0)
        set(info, "mGroupUUID", null)
        set(info, "mIsOnlyNonTerrestrialNetwork", false)
        set(info, "mType", 0)
        set(info, "mPortIndex", -1)
        set(info, "mIsOpportunistic", false)
        set(info, "mServiceCapabilities", emptySet<Int>())
        set(info, "mUsageSetting", 0)
        set(info, "mStreamingAppMaxDownlinkKbps", 0L)
        set(info, "mStreamingAppMaxUplinkKbps", 0L)
        set(info, "mNativeAccessRules", emptyList<Any>())
        info
    }.onFailure {
        Logger.warn("Virtual SubscriptionInfo construction failed: ${it.javaClass.simpleName}")
    }.getOrNull()

    private fun allocate(): SubscriptionInfo {
        // Framework filters the package-private constructor in some processes. Allocate the
        // platform object without invoking it, then initialize every field getters rely on.
        return runCatching {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
                .get(null)
            unsafeClass.getMethod("allocateInstance", Class::class.java)
                .invoke(unsafe, SubscriptionInfo::class.java) as SubscriptionInfo
        }.getOrElse {
            Reflection.newInstance(SubscriptionInfo::class.java) as SubscriptionInfo
        }
    }

    private fun set(target: Any, name: String, value: Any?) {
        val field = Reflection.findFieldIfExists(target.javaClass, name) ?: return
        runCatching { field.set(target, value) }
    }
}
