package com.system.location.service.hook.hooks.telephony

import com.system.location.service.core.environment.EnvironmentProfile
import com.system.location.service.hook.utils.BinderUtils
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger
import io.github.libxposed.api.XposedInterface.Hooker
import com.system.location.service.hook.utils.hookAllMethods

/**
 * P2-05: align SIM operator strings with the replayed cell profile for third-party callers.
 * Without a captured profile the real values are untouched, so this is a no-op by default.
 */
object SimOperatorHooks {
    fun hook(classLoader: ClassLoader) {
        val manager = com.system.location.service.hook.runtime.Reflection
            .findClassIfExists("com.android.phone.PhoneInterfaceManager", classLoader) ?: return

        val numeric = Hooker { chain ->
            if (!FakeLoc.enable || BinderUtils.isSystemAppsCall()) return@Hooker chain.proceed()
            if (!FakeLoc.simReady) {
                return@Hooker ""
            }
            val profile = FakeLoc.replayEnvironment ?: return@Hooker chain.proceed()
            val result = operatorNumeric(profile) ?: return@Hooker chain.proceed()
            if (FakeLoc.enableDebugLog) Logger.debug("${chain.executable.name}: SIM operator $result")
            result
        }
        listOf(
            "getSimOperatorNumericForPhone",
            "getSimOperatorNumericForSubscriber",
            "getSimOperatorNumeric",
        ).forEach { manager.hookAllMethods(it, numeric) }

        val name = Hooker { chain ->
            if (!FakeLoc.enable || BinderUtils.isSystemAppsCall()) return@Hooker chain.proceed()
            if (!FakeLoc.simReady) {
                return@Hooker ""
            }
            val profile = FakeLoc.replayEnvironment ?: return@Hooker chain.proceed()
            if (operatorNumeric(profile) == null) return@Hooker chain.proceed()
            if (FakeLoc.enableDebugLog) Logger.debug("${chain.executable.name}: SIM operator name Portal")
            "Portal"
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
