package com.system.location.service.hook.hooks.telephony

import android.telephony.TelephonyManager
import com.system.location.service.hook.utils.BinderUtils
import com.system.location.service.hook.utils.FakeLoc
import io.github.libxposed.api.XposedInterface.Hooker
import com.system.location.service.hook.utils.hookAllMethods
import com.system.location.service.hook.hooks.ListenerRouting

/** Returns radio types derived from the caller's applicable target cell profile. */
object PhoneTypeHooks {
    fun hook(classLoader: ClassLoader) {
        val manager = com.system.location.service.hook.runtime.Reflection
            .findClassIfExists("com.android.phone.PhoneInterfaceManager", classLoader) ?: return

        val gsmPhoneType = Hooker { chain ->
            val original = chain.proceed()
            val state = FakeLoc.runtimeSnapshots.current()
            if (state.configuration.carrierIdentityEnabled) {
                if (original == 0) return@Hooker original
                return@Hooker CarrierIdentityRuntime.profileForCall(chain.executable.name, chain.args)
                    ?.let { TelephonyManager.PHONE_TYPE_GSM } ?: original
            }
            val route = ListenerRouting.decisionFor(null, BinderUtils.getCallerUid(), state.routing)
            if (!TelephonyCallerPolicy.shouldRewrite(
                    state.configuration.enabled,
                    BinderUtils.isSystemAppsCall(),
                    route,
                )
            ) return@Hooker original
            val profile = CellInfoSynthesis.profileForRoute(state, route)
            telephonyTargetIdentity(profile)?.phoneType ?: TelephonyManager.PHONE_TYPE_NONE
        }
        listOf("getActivePhoneType", "getActivePhoneTypeForSlot").forEach {
            manager.hookAllMethods(it, gsmPhoneType)
        }

        val networkType = Hooker { chain ->
            val original = chain.proceed()
            val state = FakeLoc.runtimeSnapshots.current()
            if (state.configuration.carrierIdentityEnabled) {
                if (original == 0) return@Hooker original
                return@Hooker CarrierIdentityRuntime.profileForCall(chain.executable.name, chain.args)
                    ?.let {
                        if (it.radio == com.system.location.service.core.carrier.CarrierRadio.NR)
                            TelephonyManager.NETWORK_TYPE_NR else TelephonyManager.NETWORK_TYPE_LTE
                    } ?: original
            }
            val route = ListenerRouting.decisionFor(null, BinderUtils.getCallerUid(), state.routing)
            if (!TelephonyCallerPolicy.shouldRewrite(
                    state.configuration.enabled,
                    BinderUtils.isSystemAppsCall(),
                    route,
                )
            ) return@Hooker original
            val profile = CellInfoSynthesis.profileForRoute(state, route)
            telephonyTargetIdentity(profile)?.networkType ?: TelephonyManager.NETWORK_TYPE_UNKNOWN
        }
        listOf(
            "getNetworkType",
            "getDataNetworkType",
            "getNetworkTypeForSubscriber",
            "getDataNetworkTypeForSubscriber",
        ).forEach { manager.hookAllMethods(it, networkType) }
    }
}
