package com.system.location.service.hook.hooks.telephony

import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import com.system.location.service.core.carrier.CarrierRadio
import com.system.location.service.core.carrier.SimCarrierProfile
import com.system.location.service.hook.runtime.Reflection

/** SA reports NR as the underlying RAT. LTE+NR override values describe NSA, not SA. */
internal object CarrierDisplayInfo {
    fun create(profile: SimCarrierProfile): TelephonyDisplayInfo =
        Reflection.newInstance(TelephonyDisplayInfo::class.java,
            if (profile.radio == CarrierRadio.NR) TelephonyManager.NETWORK_TYPE_NR
            else TelephonyManager.NETWORK_TYPE_LTE,
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE) as TelephonyDisplayInfo

    fun unavailable(): TelephonyDisplayInfo = Reflection.newInstance(TelephonyDisplayInfo::class.java,
        TelephonyManager.NETWORK_TYPE_UNKNOWN, TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE) as TelephonyDisplayInfo
}
