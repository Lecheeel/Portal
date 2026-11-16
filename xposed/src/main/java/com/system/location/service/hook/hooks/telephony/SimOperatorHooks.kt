package com.system.location.service.hook.hooks.telephony

import com.system.location.service.core.carrier.CarrierVirtualIdentity
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

        manager.hookAllMethods("getMergedImsisFromGroup", Hooker { chain ->
            val profile = CarrierIdentityRuntime.profileForCall(chain.executable.name, chain.args)
            if (profile?.virtual != true) return@Hooker chain.proceed()
            val context = BinderUtils.getSystemContext() ?: return@Hooker chain.proceed()
            // This API requires READ_PRIVILEGED_PHONE_STATE. Preserve the original check for
            // every caller not holding it; never convert an invalid real subId into an identity.
            if (context.checkCallingOrSelfPermission("android.permission.READ_PRIVILEGED_PHONE_STATE") !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) return@Hooker chain.proceed()
            arrayOf(CarrierVirtualIdentity.imsi(profile))
        })

        val numeric = Hooker { chain ->
            val original = chain.proceed()
            val state = FakeLoc.runtimeSnapshots.current()
            if (state.configuration.carrierIdentityEnabled) {
                return@Hooker CarrierIdentityRuntime.profileForCall(chain.executable.name, chain.args)
                    ?.operatorNumeric ?: original
            }
            val route = ListenerRouting.decisionFor(null, BinderUtils.getCallerUid(), state.routing)
            if (!TelephonyCallerPolicy.shouldRewrite(
                    state.configuration.enabled,
                    BinderUtils.isSystemAppsCall(),
                    route,
                )
            ) return@Hooker original
            val carrier = CarrierIdentityRuntime.profileForCall(chain.executable.name, chain.args)
            if (carrier != null) return@Hooker carrier.operatorNumeric
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
            if (state.configuration.carrierIdentityEnabled) {
                return@Hooker CarrierIdentityRuntime.profileForCall(chain.executable.name, chain.args)
                    ?.carrierName ?: original
            }
            val route = ListenerRouting.decisionFor(null, BinderUtils.getCallerUid(), state.routing)
            if (!TelephonyCallerPolicy.shouldRewrite(
                    state.configuration.enabled,
                    BinderUtils.isSystemAppsCall(),
                    route,
                )
            ) return@Hooker original
            val carrier = CarrierIdentityRuntime.profileForCall(chain.executable.name, chain.args)
            if (carrier != null) return@Hooker carrier.carrierName
            val profile = CellInfoSynthesis.profileForRoute(state, route)
            telephonyTargetIdentity(profile)?.operatorName.orEmpty()
        }
        listOf(
            "getSimOperatorNameForPhone",
            "getSimOperatorNameForSubscriber",
            "getSimOperatorName",
        ).forEach { manager.hookAllMethods(it, name) }

        // These methods are version-dependent. hookAllMethods simply installs nothing when a
        // ROM lacks an entry; original execution still enforces framework permission semantics.
        val networkNumeric = Hooker { chain ->
            val original = chain.proceed()
            CarrierIdentityRuntime.profileForCall(chain.executable.name, chain.args)
                ?.operatorNumeric ?: original
        }
        listOf("getNetworkOperatorForPhone", "getNetworkOperatorForSubscriber", "getNetworkOperator")
            .forEach { manager.hookAllMethods(it, networkNumeric) }
        val networkName = Hooker { chain ->
            val original = chain.proceed()
            CarrierIdentityRuntime.profileForCall(chain.executable.name, chain.args)
                ?.operatorName ?: original
        }
        listOf("getNetworkOperatorNameForPhone", "getNetworkOperatorNameForSubscriber", "getNetworkOperatorName")
            .forEach { manager.hookAllMethods(it, networkName) }
        val country = Hooker { chain ->
            val original = chain.proceed()
            CarrierIdentityRuntime.profileForCall(chain.executable.name, chain.args)
                ?.countryIso?.lowercase(java.util.Locale.ROOT) ?: original
        }
        listOf("getSimCountryIsoForPhone", "getSimCountryIsoForSubscriber", "getSimCountryIso",
            "getNetworkCountryIsoForPhone", "getNetworkCountryIsoForSubscriber", "getNetworkCountryIso")
            .forEach { manager.hookAllMethods(it, country) }

        val simState = Hooker { chain ->
            val original = chain.proceed()
            val profile = CarrierIdentityRuntime.profileForCall(chain.executable.name, chain.args)
            if (profile?.virtual == true) android.telephony.TelephonyManager.SIM_STATE_READY else original
        }
        listOf("getSimStateForSlotIndex", "getSimStateForSubscriber", "getSimState")
            .forEach { manager.hookAllMethods(it, simState) }

        val subscriber = Hooker { chain ->
            val original = chain.proceed()
            val profile = CarrierIdentityRuntime.profileForCall(chain.executable.name, chain.args)
            if (profile?.virtual == true) CarrierVirtualIdentity.imsi(profile) else original
        }
        listOf("getSubscriberIdForSubscriber", "getSubscriberId", "getSubscriberIdForPhone")
            .forEach { manager.hookAllMethods(it, subscriber) }

        val icc = Hooker { chain ->
            val original = chain.proceed()
            val profile = CarrierIdentityRuntime.profileForCall(chain.executable.name, chain.args)
            if (profile?.virtual == true) CarrierVirtualIdentity.iccid(profile) else original
        }
        listOf("getIccIdForSubscriber", "getIccId", "getSimSerialNumberForSubscriber",
            "getSimSerialNumber").forEach { manager.hookAllMethods(it, icc) }
    }
}
