package com.system.location.service.hook.hooks.telephony

import android.telephony.TelephonyManager
import com.system.location.service.hook.utils.BinderUtils
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger
import io.github.libxposed.api.XposedInterface.Hooker
import com.system.location.service.hook.utils.hookAllMethods

/**
 * P2-05: keep phone/network type consistent with the device's own modem and SIM, not with a
 * hard-coded LTE profile. The framework path runs first so a caller without READ_PHONE_STATE
 * still observes the platform's permission result.
 */
object PhoneTypeHooks {
    fun hook(classLoader: ClassLoader) {
        val manager = com.system.location.service.hook.runtime.Reflection
            .findClassIfExists("com.android.phone.PhoneInterfaceManager", classLoader) ?: return

        val gsmPhoneType = Hooker { chain ->
            val original = chain.proceed()
            if (!FakeLoc.enable || BinderUtils.isSystemAppsCall()) return@Hooker original
            val sim = TelephonyFacade.sim()
            if (!FakeLoc.simReady || !sim.ready) TelephonyManager.PHONE_TYPE_NONE
            else TelephonyFacade.phoneType()
        }
        listOf("getActivePhoneType", "getActivePhoneTypeForSlot").forEach {
            manager.hookAllMethods(it, gsmPhoneType)
        }

        val networkType = Hooker { chain ->
            val original = chain.proceed()
            if (!FakeLoc.enable || BinderUtils.isSystemAppsCall()) return@Hooker original
            val sim = TelephonyFacade.sim()
            if (!FakeLoc.simReady || !sim.ready) TelephonyManager.NETWORK_TYPE_UNKNOWN
            else TelephonyFacade.networkType().takeIf { it != TelephonyManager.NETWORK_TYPE_UNKNOWN }
                ?: original
        }
        listOf(
            "getNetworkType",
            "getDataNetworkType",
            "getNetworkTypeForSubscriber",
            "getDataNetworkTypeForSubscriber",
        ).forEach { manager.hookAllMethods(it, networkType) }
    }
}
