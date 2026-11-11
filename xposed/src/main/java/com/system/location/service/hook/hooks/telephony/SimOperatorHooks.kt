package com.system.location.service.hook.hooks.telephony

import com.system.location.service.hook.utils.BinderUtils
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger
import io.github.libxposed.api.XposedInterface.Hooker
import com.system.location.service.hook.utils.hookAllMethods

/**
 * P2-05: align SIM operator strings with the device's real SIM (or the captured replay profile).
 *
 * The previous implementation returned the literal `"Portal"` for the operator name, which is a
 * project fingerprint comparable to `kail_faked`; it must never be exposed to an application.
 */
object SimOperatorHooks {
    fun hook(classLoader: ClassLoader) {
        val manager = com.system.location.service.hook.runtime.Reflection
            .findClassIfExists("com.android.phone.PhoneInterfaceManager", classLoader) ?: return

        val numeric = Hooker { chain ->
            val original = chain.proceed()
            if (!FakeLoc.enable || BinderUtils.isSystemAppsCall()) return@Hooker original
            val sim = TelephonyFacade.sim()
            if (!FakeLoc.simReady || !sim.ready) return@Hooker ""
            val replay = FakeLoc.replayEnvironment?.cells?.firstOrNull()
            val value = replay?.let { "%03d%02d".format(it.mcc, it.mnc) } ?: sim.numeric
            value.ifBlank { original as? String ?: "" }
        }
        listOf(
            "getSimOperatorNumericForPhone",
            "getSimOperatorNumericForSubscriber",
            "getSimOperatorNumeric",
        ).forEach { manager.hookAllMethods(it, numeric) }

        val name = Hooker { chain ->
            val original = chain.proceed()
            if (!FakeLoc.enable || BinderUtils.isSystemAppsCall()) return@Hooker original
            val sim = TelephonyFacade.sim()
            if (!FakeLoc.simReady || !sim.ready) return@Hooker ""
            sim.name.ifBlank { original as? String ?: "" }
        }
        listOf(
            "getSimOperatorNameForPhone",
            "getSimOperatorNameForSubscriber",
            "getSimOperatorName",
        ).forEach { manager.hookAllMethods(it, name) }
    }
}
