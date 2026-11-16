package com.system.location.service.hook.hooks.telephony

import android.telephony.SubscriptionInfo
import com.system.location.service.core.carrier.CarrierVirtualIdentity
import com.system.location.service.core.carrier.SimCarrierProfile
import com.system.location.service.hook.runtime.Reflection
import com.system.location.service.hook.utils.Logger
import java.util.Locale

/** Constructs a complete platform object, including platform defaults and derived fields. */
internal object CarrierVirtualSubscription {
    fun info(profile: SimCarrierProfile): SubscriptionInfo? = create(profile).onFailure {
        Logger.warn("Virtual SubscriptionInfo construction failed: ${it.javaClass.simpleName}")
    }.getOrNull()

    internal fun create(profile: SimCarrierProfile): Result<SubscriptionInfo> = runCatching {
        val builderClass = Class.forName("android.telephony.SubscriptionInfo\$Builder")
        val builder = Reflection.newInstance(builderClass)
        fun set(name: String, value: Any) { Reflection.callMethod(builder, name, value) }
        set("setId", CarrierVirtualIdentity.subscriptionId(profile.slot))
        set("setIccId", CarrierVirtualIdentity.iccid(profile))
        set("setSimSlotIndex", profile.slot - 1)
        set("setDisplayName", profile.operatorName)
        set("setCarrierName", profile.carrierName)
        set("setMcc", profile.mcc.toString().padStart(3, '0'))
        set("setMnc", profile.mnc.toString().padStart(profile.mncDigits, '0'))
        set("setCountryIso", profile.countryIso.lowercase(Locale.ROOT))
        set("setCarrierId", CarrierVirtualIdentity.carrierId(profile))
        // A virtual logical UICC port within its physical slot. Other fields deliberately keep
        // the framework Builder defaults, rather than Unsafe's null/zero uninitialized fields.
        set("setPortIndex", 0)
        val info = Reflection.callMethod(builder, "build") as SubscriptionInfo
        check(info.subscriptionId == CarrierVirtualIdentity.subscriptionId(profile.slot))
        check(info.simSlotIndex == profile.slot - 1)
        check(info.mccString == profile.mcc.toString().padStart(3, '0'))
        check(info.mncString == profile.mnc.toString().padStart(profile.mncDigits, '0'))
        info
    }
}
