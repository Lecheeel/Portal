package com.system.location.service.hook.hooks.telephony

import com.system.location.service.core.environment.EnvironmentProfile
import com.system.location.service.hook.utils.BinderUtils
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger
import com.system.location.service.hook.utils.beforeHook
import com.system.location.service.hook.utils.hookAllMethods

/**
 * P2-05: align SIM operator strings with the replayed cell profile for third-party callers.
 * Without a captured profile the real values are untouched, so this is a no-op by default.
 */
object SimOperatorHooks {
    fun hook(classLoader: ClassLoader) {
        val manager = de.robv.android.xposed.XposedHelpers
            .findClassIfExists("com.android.phone.PhoneInterfaceManager", classLoader) ?: return

        val numeric = beforeHook {
            if (!FakeLoc.enable || BinderUtils.isSystemAppsCall()) return@beforeHook
            val profile = FakeLoc.replayEnvironment ?: return@beforeHook
            result = operatorNumeric(profile) ?: return@beforeHook
            if (FakeLoc.enableDebugLog) Logger.debug("${method.name}: SIM operator $result")
        }
        listOf(
            "getSimOperatorNumericForPhone",
            "getSimOperatorNumericForSubscriber",
            "getSimOperatorNumeric",
        ).forEach { manager.hookAllMethods(it, numeric) }

        val name = beforeHook {
            if (!FakeLoc.enable || BinderUtils.isSystemAppsCall()) return@beforeHook
            val profile = FakeLoc.replayEnvironment ?: return@beforeHook
            if (operatorNumeric(profile) == null) return@beforeHook
            result = "Portal"
            if (FakeLoc.enableDebugLog) Logger.debug("${method.name}: SIM operator name Portal")
        }
        listOf(
            "getSimOperatorNameForPhone",
            "getSimOperatorNameForSubscriber",
            "getSimOperatorName",
        ).forEach { manager.hookAllMethods(it, name) }
    }

    private fun operatorNumeric(profile: EnvironmentProfile): String? {
        val cell = profile.cells.firstOrNull() ?: return null
        if (cell.mcc !in 1..999 || cell.mnc !in 0..999) return null
        return "%03d%02d".format(cell.mcc, cell.mnc)
    }
}
