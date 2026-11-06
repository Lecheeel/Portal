package com.system.location.service.hook.hooks.telephony

import android.telephony.TelephonyManager
import com.system.location.service.hook.utils.BinderUtils
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger
import com.system.location.service.hook.utils.beforeHook
import com.system.location.service.hook.utils.hookAllMethods

/**
 * P2-05: keep phone/network type consistent with the LTE replay profile for third-party callers.
 * System callers keep real values. The deprecated CDMA path (when explicitly enabled) is installed
 * later by TelephonyHook and still wins on those devices.
 */
object PhoneTypeHooks {
    fun hook(classLoader: ClassLoader) {
        val manager = de.robv.android.xposed.XposedHelpers
            .findClassIfExists("com.android.phone.PhoneInterfaceManager", classLoader) ?: return

        val gsmPhoneType = beforeHook {
            if (!FakeLoc.enable || BinderUtils.isSystemAppsCall()) return@beforeHook
            result = TelephonyManager.PHONE_TYPE_GSM
            if (FakeLoc.enableDebugLog) Logger.debug("${method.name}: PHONE_TYPE_GSM")
        }
        listOf("getActivePhoneType", "getActivePhoneTypeForSlot").forEach {
            manager.hookAllMethods(it, gsmPhoneType)
        }

        val lteNetwork = beforeHook {
            if (!FakeLoc.enable || BinderUtils.isSystemAppsCall()) return@beforeHook
            result = TelephonyManager.NETWORK_TYPE_LTE
            if (FakeLoc.enableDebugLog) Logger.debug("${method.name}: NETWORK_TYPE_LTE")
        }
        listOf(
            "getNetworkType",
            "getDataNetworkType",
            "getNetworkTypeForSubscriber",
            "getDataNetworkTypeForSubscriber",
        ).forEach { manager.hookAllMethods(it, lteNetwork) }
    }
}
