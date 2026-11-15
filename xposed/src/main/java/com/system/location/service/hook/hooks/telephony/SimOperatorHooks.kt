package com.system.location.service.hook.hooks.telephony

import com.system.location.service.hook.utils.BinderUtils
import com.system.location.service.hook.utils.FakeLoc
import io.github.libxposed.api.XposedInterface.Hooker
import com.system.location.service.hook.utils.hookAllMethods
import com.system.location.service.hook.hooks.ListenerRouting

/** Returns operator strings only when they are present in the caller's applicable target profile. */
object SimOperatorHooks {
    fun hook(classLoader: ClassLoader) {
        val manager = com.system.location.service.hook.runtime.Reflection
            .findClassIfExists("com.android.phone.PhoneInterfaceManager", classLoader) ?: return

        val numeric = Hooker { chain ->
            val original = chain.proceed()
            val state = FakeLoc.runtimeSnapshots.current()
            val route = ListenerRouting.decisionFor(null, BinderUtils.getCallerUid(), state.routing)
            if (!TelephonyCallerPolicy.shouldRewrite(
                    state.configuration.enabled,
                    BinderUtils.isSystemAppsCall(),
                    route,
                )
            ) return@Hooker original
            val profile = CellInfoSynthesis.profileForRoute(state, route)
            telephonyTargetIdentity(profile)?.operatorNumeric.orEmpty()
        }
        listOf(
            "getSimOperatorNumericForPhone",
            "getSimOperatorNumericForSubscriber",
            "getSimOperatorNumeric",
        ).forEach { manager.hookAllMethods(it, numeric) }

        val name = Hooker { chain ->
            val original = chain.proceed()
            val state = FakeLoc.runtimeSnapshots.current()
            val route = ListenerRouting.decisionFor(null, BinderUtils.getCallerUid(), state.routing)
            if (!TelephonyCallerPolicy.shouldRewrite(
                    state.configuration.enabled,
                    BinderUtils.isSystemAppsCall(),
                    route,
                )
            ) return@Hooker original
            val profile = CellInfoSynthesis.profileForRoute(state, route)
            telephonyTargetIdentity(profile)?.operatorName.orEmpty()
        }
        listOf(
            "getSimOperatorNameForPhone",
            "getSimOperatorNameForSubscriber",
            "getSimOperatorName",
        ).forEach { manager.hookAllMethods(it, name) }
    }
}
