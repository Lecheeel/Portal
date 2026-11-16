package com.system.location.service.hook.hooks.telephony

import android.os.Binder
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import com.system.location.service.core.carrier.CarrierIdentityConfig
import com.system.location.service.core.carrier.CarrierIdentityMode
import com.system.location.service.core.carrier.CarrierIdentityResolver
import com.system.location.service.core.carrier.CarrierTarget
import com.system.location.service.core.carrier.CarrierVirtualIdentity
import com.system.location.service.core.carrier.SimCarrierProfile
import com.system.location.service.hook.utils.BinderUtils
import com.system.location.service.hook.utils.FakeLoc
import java.util.Locale

/** Resolves physical subscriptions under system identity; caller identity is captured beforehand. */
internal object CarrierIdentityRuntime {
    internal const val PHYSICAL_QUERY_TAG = "portal.carrier.physical-topology"
    private val resolving = ThreadLocal.withInitial { false }

    internal fun protectsLocation(uid: Int): Boolean {
        val state = FakeLoc.runtimeSnapshots.current()
        if (!state.configuration.enabled || uid < 0 || uid % 100000 < 10000) return false
        val context = BinderUtils.getSystemContext() ?: return true
        val packages = runCatching { context.packageManager.getPackagesForUid(uid)?.toList().orEmpty() }
            .getOrDefault(emptyList())
        if (packages.isEmpty()) return true
        if (packages.any { name -> runCatching {
            context.packageManager.getApplicationInfo(name, 0).flags and
                (android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        }.getOrDefault(false) }) return false
        return runCatching {
            com.system.location.service.hook.hooks.ListenerRouting.decisionFor(null, uid, state.routing) !=
                com.system.location.service.core.routing.RoutingDecision.Deny
        }.getOrDefault(true)
    }

    fun profileForCall(methodName: String, args: List<Any?>,
        callerUid: Int = Binder.getCallingUid()): SimCarrierProfile? {
        val policy = FakeLoc.runtimeSnapshots.current().configuration
        if (!policy.enabled || !policy.carrierIdentityEnabled || resolving.get() == true) return null
        val config = policy.carrierIdentity ?: return null
        val context = BinderUtils.getSystemContext() ?: return null
        val packages = packagesFor(context, callerUid)
        val systemCaller = isSystemCaller(context, callerUid, packages)
        resolving.set(true)
        val token = Binder.clearCallingIdentity()
        return try {
            val manager = context.createAttributionContext(PHYSICAL_QUERY_TAG)
                .getSystemService(SubscriptionManager::class.java) ?: return null
            val physical = physicalSubscriptions(manager)
            val physicalSlots = physical.map { it.simSlotIndex + 1 }.toSet()
            val virtual = virtualProfiles(config, physicalSlots, packages, systemCaller)
                .filter { profile -> physical.none { it.subscriptionId == CarrierVirtualIdentity.subscriptionId(profile) } }
            val combined = physical.associate { it.subscriptionId to (it.simSlotIndex + 1) } +
                virtual.associate { CarrierVirtualIdentity.subscriptionId(it) to it.slot }
            val defaultSubId: Int = physical.firstOrNull { it.subscriptionId ==
                SubscriptionManager.getDefaultSubscriptionId() }?.subscriptionId
                ?: virtual.firstOrNull()?.let { CarrierVirtualIdentity.subscriptionId(it) }
                ?: SubscriptionManager.getDefaultSubscriptionId()
            val requested = args.firstOrNull() as? Int
            val phoneIndex = methodName.endsWith("ForPhone") || methodName.endsWith("ForSlot")
            val slot = if (methodName.contains("SimSlotIndex") || methodName.contains("ForSlotIndex")) {
                requested?.takeIf { it in 0..1 }?.plus(1)
            } else if (phoneIndex && requested == null) {
                // Some PhoneInterfaceManager getters omit the phone index and resolve the current
                // default phone internally. Treat them like the default subscription.
                combined[defaultSubId] ?: combined.values.firstOrNull()
            } else {
                com.system.location.service.core.carrier.CarrierSubscriptionSelection.slot(
                    methodName, requested, combined, defaultSubId,
                )
            }
            val resolvedSlot = slot ?: return null
            CarrierIdentityResolver.profile(config, resolvedSlot, physicalSlots, packages, systemCaller)
        } catch (_: Exception) {
            // An unavailable mapping cannot be guessed: preserve the platform result.
            null
        } finally {
            Binder.restoreCallingIdentity(token)
            resolving.set(false)
        }
    }

    /** Virtual profiles visible to this caller; used to synthesize the SubscriptionInfo list. */
    internal fun virtualProfilesForCall(callerUid: Int = Binder.getCallingUid()): List<SimCarrierProfile> {
        val policy = FakeLoc.runtimeSnapshots.current().configuration
        if (!policy.enabled || !policy.carrierIdentityEnabled || resolving.get() == true) return emptyList()
        val config = policy.carrierIdentity ?: return emptyList()
        val context = BinderUtils.getSystemContext() ?: return emptyList()
        val packages = packagesFor(context, callerUid)
        val systemCaller = isSystemCaller(context, callerUid, packages)
        resolving.set(true)
        val token = Binder.clearCallingIdentity()
        return try {
            val manager = context.createAttributionContext(PHYSICAL_QUERY_TAG)
                .getSystemService(SubscriptionManager::class.java) ?: return emptyList()
            val physical = physicalSubscriptions(manager)
            virtualProfiles(config, physical.map { it.simSlotIndex + 1 }.toSet(), packages, systemCaller)
                .filter { profile -> physical.none { it.subscriptionId == CarrierVirtualIdentity.subscriptionId(profile) } }
        } catch (_: Exception) {
            emptyList()
        } finally {
            Binder.restoreCallingIdentity(token)
            resolving.set(false)
        }
    }

    internal fun isResolving(): Boolean = resolving.get() == true

    private fun physicalSubscriptions(manager: SubscriptionManager): List<SubscriptionInfo> =
        manager.activeSubscriptionInfoList.orEmpty()
            .filter { it.simSlotIndex in 0..1 && SubscriptionManager.isValidSubscriptionId(it.subscriptionId) }
            // The SubscriptionInfo hook may have already added our synthetic slots in this
            // process. Never feed them back into the physical-slot resolver.
            .filter { info ->
                val modeled = FakeLoc.runtimeSnapshots.current().configuration.carrierIdentity?.sims.orEmpty()
                    .firstOrNull { CarrierVirtualIdentity.subscriptionId(it) == info.subscriptionId }
                modeled == null || info.iccId != CarrierVirtualIdentity.iccid(modeled)
            }

    private fun virtualProfiles(
        config: CarrierIdentityConfig,
        physicalSlots: Set<Int>,
        packages: List<String>,
        systemCaller: Boolean,
    ): List<SimCarrierProfile> {
        if (config.mode == CarrierIdentityMode.GLOBAL) {
            return config.sims.filter { it.enabled && it.virtual && it.slot !in physicalSlots }
                .map { it.copy(virtual = true) }
        }
        if (systemCaller || packages.isEmpty()) return emptyList()
        val targets = packages.map { config.targetFor(it, false) }.distinct()
        if (targets.size != 1) return emptyList()
        val target = targets.single()
        if (target == CarrierTarget.REAL) return emptyList()
        if (target == CarrierTarget.INHERIT) {
            // A selected app with the default route inherits the physical slot. On a no-SIM
            // fixture that slot does not exist, so inherit the first explicitly configured
            // virtual slot rather than silently disabling per-app identity.
            return config.sims.filter { it.enabled && it.virtual && it.slot !in physicalSlots }
                .sortedBy { it.slot }.take(1).map { it.copy(virtual = true) }
        }
        val slot = when (target) {
            CarrierTarget.SIM1 -> 1
            CarrierTarget.SIM2 -> 2
            else -> return emptyList()
        }
        if (slot in physicalSlots) return emptyList()
        return listOfNotNull(config.profileForSlot(slot)?.takeIf { it.virtual })
    }

    private fun packagesFor(context: android.content.Context, uid: Int): List<String> =
        runCatching { context.packageManager.getPackagesForUid(uid)?.toList().orEmpty() }
            .getOrDefault(emptyList())

    private fun isSystemCaller(
        context: android.content.Context,
        uid: Int,
        packages: List<String>,
    ): Boolean = uid % 100000 < 10000 || packages.isEmpty() || packages.any { name ->
        runCatching {
            val flags = context.packageManager.getApplicationInfo(name, 0).flags
            flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or
                android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        }.getOrDefault(true)
    }
}
