package com.system.location.service.hook.hooks.telephony

import android.telephony.TelephonyManager
import com.system.location.service.hook.utils.BinderUtils
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger
import io.github.libxposed.api.XposedInterface.Hooker
import com.system.location.service.hook.utils.hookAllMethods

/**
 * P2-05: keep phone/network type consistent with the LTE replay profile for third-party callers.
 * System callers keep real values. The deprecated CDMA path (when explicitly enabled) is installed
 * later by TelephonyHook and still wins on those devices.
 */
object PhoneTypeHooks {
    fun hook(classLoader: ClassLoader) {
        val manager = com.system.location.service.hook.runtime.Reflection
            .findClassIfExists("com.android.phone.PhoneInterfaceManager", classLoader) ?: return

        val gsmPhoneType = Hooker { chain ->
            if (!FakeLoc.enable || BinderUtils.isSystemAppsCall()) return@Hooker chain.proceed()
            val result = if (FakeLoc.simReady) TelephonyManager.PHONE_TYPE_GSM else TelephonyManager.PHONE_TYPE_NONE
            if (FakeLoc.enableDebugLog) Logger.debug("${chain.executable.name}: $result")
            result
        }
        listOf("getActivePhoneType", "getActivePhoneTypeForSlot").forEach {
            manager.hookAllMethods(it, gsmPhoneType)
        }

        val lteNetwork = Hooker { chain ->
            if (!FakeLoc.enable || BinderUtils.isSystemAppsCall()) return@Hooker chain.proceed()
            val result = if (FakeLoc.simReady) TelephonyManager.NETWORK_TYPE_LTE else TelephonyManager.NETWORK_TYPE_UNKNOWN
            if (FakeLoc.enableDebugLog) Logger.debug("${chain.executable.name}: $result")
            result
        }
        listOf(
            "getNetworkType",
            "getDataNetworkType",
            "getNetworkTypeForSubscriber",
            "getDataNetworkTypeForSubscriber",
        ).forEach { manager.hookAllMethods(it, lteNetwork) }
    }
}
