package com.system.location.service.hook.hooks.telephony

import android.os.Binder
import android.os.IBinder
import com.system.location.service.hook.utils.BinderUtils
import android.os.PersistableBundle
import android.telephony.SubscriptionManager
import com.system.location.service.core.carrier.CarrierIdentityMode
import com.system.location.service.core.carrier.CarrierVirtualIdentity
import com.system.location.service.core.carrier.SimCarrierProfile
import com.system.location.service.core.runtime.RuntimeSnapshot
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger

/**
 * GLOBAL-mode implementation. It deliberately applies the same public CarrierConfig override
 * mechanism used by the OS: the system phone stack, SystemUI and third-party apps all observe the
 * simulated identity. PER_APP mode never calls this controller.
 *
 * The override is non-persistent and is removed when the feature is disabled or the scenario stops.
 */
object CarrierConfigGlobalController {
    private const val COUNTRY = "sim_country_iso_override_string"
    private const val NAME_OVERRIDE = "carrier_name_override_bool"
    private const val NAME = "carrier_name_string"
    private const val HOME = "force_home_network_bool"

    private var appliedSubIds: Set<Int> = emptySet()
    @Volatile var status: String = "disabled"
        private set

    fun recordFailure() { status = "failed" }

    fun supportsOwnedOverrides(): Boolean {
        if (!FakeLoc.isSystemServerProcess) return false
        return withSystemIdentity {
            runCatching {
                val loader = carrierConfigLoader() ?: return@runCatching false
                val reply = loader.javaClass.getMethod("getConfigForSubIdWithFeature",
                    Int::class.javaPrimitiveType, String::class.java, String::class.java)
                    .invoke(loader, SubscriptionManager.INVALID_SUBSCRIPTION_ID, "android",
                        CarrierOverrideProtocol.FEATURE) as? PersistableBundle
                reply?.getBoolean(CarrierOverrideProtocol.SUPPORTED) == true
            }.getOrDefault(false)
        }
    }

    @Synchronized
    fun saveReloadState(): android.os.Bundle = android.os.Bundle().apply {
        putIntArray("subscriptions", appliedSubIds.toIntArray())
        putString("status", status)
    }

    @Synchronized
    fun restoreReloadState(saved: android.os.Bundle?) {
        if (saved == null) return
        appliedSubIds = (saved.getIntArray("subscriptions") ?: intArrayOf())
            .filter { SubscriptionManager.isValidSubscriptionId(it) }.toSet()
        status = saved.getString("status") ?: "restore_pending"
    }

    @Synchronized
    fun onSimulationStopped() {
        if (!FakeLoc.isSystemServerProcess) return
        withSystemIdentity { restoreApplied() }
    }

    @Synchronized
    fun onConfigurationChanged(state: RuntimeSnapshot) {
        if (!FakeLoc.isSystemServerProcess) return
        withSystemIdentity { applyConfiguration(state) }
    }

    private inline fun <T> withSystemIdentity(action: () -> T): T {
        val token = Binder.clearCallingIdentity()
        return try { action() } finally { Binder.restoreCallingIdentity(token) }
    }

    private fun applyConfiguration(state: RuntimeSnapshot) {
        val policy = state.configuration
        val config = policy.carrierIdentity
        val enabled = policy.enabled && policy.carrierIdentityEnabled &&
            config != null && config.mode == CarrierIdentityMode.GLOBAL
        if (!enabled) {
            restoreApplied()
            status = when {
                appliedSubIds.isNotEmpty() -> "restore_pending"
                !policy.carrierIdentityEnabled -> "disabled"
                !policy.enabled -> "waiting_for_scenario"
                config?.mode == CarrierIdentityMode.PER_APP -> "per_app"
                else -> "unconfigured"
            }
            return
        }
        val loader = carrierConfigLoader()
        if (loader == null) {
            status = "service_unavailable"
            Logger.warn("Global carrier identity unavailable: carrier_config service not found")
            return
        }
        val next = LinkedHashSet<Int>()
        var succeeded = 0
        var failed = false
        for (slot in 1..2) {
            val profile = config?.profileForSlot(slot) ?: continue
            val subIds = subIdsForSlot(slot)
            for (subId in subIds) {
                runCatching {
                    overrideConfig(loader, subId, bundleFor(profile))
                    next += subId
                    succeeded++
                }.onFailure {
                    failed = true
                    next += subId
                    Logger.warn("Global carrier identity failed for slot=$slot sub=$subId: ${it.message}")
                }
            }
        }
        val removed = appliedSubIds - next
        for (subId in removed) {
            runCatching { overrideConfig(loader, subId, PersistableBundle().apply {
                    putString(CarrierOverrideProtocol.ACTION, CarrierOverrideProtocol.RESTORE)
                }) }.onFailure {
                // Keep ownership until restore succeeds, so disabling can be retried.
                failed = true
                next += subId
                Logger.warn("Global carrier restore failed for sub=$subId: ${it.message}")
            }
        }
        appliedSubIds = next
        status = when {
            failed -> "failed"
            succeeded == 0 && config.sims.any { it.enabled && it.virtual } -> "virtual_applied"
            succeeded == 0 -> "no_active_sim"
            else -> "global_applied"
        }
    }

    private fun restoreApplied() {
        if (appliedSubIds.isEmpty()) return
        val loader = carrierConfigLoader() ?: return
        val pending = LinkedHashSet<Int>()
        for (subId in appliedSubIds) {
            runCatching { overrideConfig(loader, subId, PersistableBundle().apply {
                    putString(CarrierOverrideProtocol.ACTION, CarrierOverrideProtocol.RESTORE)
                }) }.onFailure {
                pending += subId
                Logger.warn("Global carrier restore failed for sub=$subId: ${it.message}")
            }
        }
        appliedSubIds = pending
    }

    private fun bundleFor(profile: SimCarrierProfile): PersistableBundle = PersistableBundle().apply {
        putString(CarrierOverrideProtocol.ACTION, CarrierOverrideProtocol.APPLY)
        putString(COUNTRY, profile.countryIso.lowercase(java.util.Locale.ROOT))
        putBoolean(NAME_OVERRIDE, true)
        putString(NAME, profile.carrierName)
        // Matches the AOSP CTS carrier-name path used by Nrfr; it keeps the override active on
        // the home network rather than allowing roaming configuration to undo it.
        putBoolean(HOME, true)
    }

    private fun subIdsForSlot(slot: Int): List<Int> {
        // Resolve only active subscriptions whose real slot matches. The default data/voice
        // subscription may live in either slot and must never be guessed to belong to SIM1.
        val context = BinderUtils.getSystemContext() ?: return emptyList()
        val manager = context.createAttributionContext(CarrierIdentityRuntime.PHYSICAL_QUERY_TAG)
            .getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        return manager.activeSubscriptionInfoList.orEmpty()
            .filter { it.simSlotIndex == slot - 1 }
            .filter { info ->
                val modeled = FakeLoc.runtimeSnapshots.current().configuration.carrierIdentity?.sims.orEmpty()
                    .firstOrNull { CarrierVirtualIdentity.subscriptionId(it) == info.subscriptionId }
                modeled == null || info.iccId != CarrierVirtualIdentity.iccid(modeled)
            }
            .map { it.subscriptionId }
            .filter { SubscriptionManager.isValidSubscriptionId(it) }
            .distinct()
    }

    private fun carrierConfigLoader(): Any? = runCatching {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val binder = serviceManager.getMethod("getService", String::class.java)
            .invoke(null, "carrier_config") as? IBinder ?: return null
        val stub = Class.forName("com.android.internal.telephony.ICarrierConfigLoader\$Stub")
        stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
    }.getOrNull()

    private fun overrideConfig(loader: Any, subId: Int, bundle: PersistableBundle) {
        val probeMethod = loader.javaClass.getMethod("getConfigForSubIdWithFeature",
            Int::class.javaPrimitiveType, String::class.java, String::class.java)
        fun probe(): PersistableBundle? = probeMethod.invoke(loader, subId, "android",
            CarrierOverrideProtocol.FEATURE) as? PersistableBundle
        check(probe()?.getBoolean(CarrierOverrideProtocol.SUPPORTED) == true) {
            "Phone process does not support owned carrier overrides"
        }
        val method = loader.javaClass.methods.firstOrNull {
            it.name == "overrideConfig" &&
                it.parameterTypes.toList() == listOf(
                    Int::class.javaPrimitiveType,
                    PersistableBundle::class.java,
                    Boolean::class.javaPrimitiveType,
                )
        } ?: error("overrideConfig signature missing")
        method.invoke(loader, subId, bundle, false)
        val shouldOwn = bundle.getString(CarrierOverrideProtocol.ACTION) == CarrierOverrideProtocol.APPLY
        val deadline = android.os.SystemClock.elapsedRealtime() + 1_000L
        do {
            val state = probe()
            check(state?.getBoolean(CarrierOverrideProtocol.FAILED) != true) {
                "Phone process rejected carrier override; previous restore ownership is retained"
            }
            if (state != null && !state.getBoolean(CarrierOverrideProtocol.PENDING) &&
                state.getBoolean(CarrierOverrideProtocol.OWNED) == shouldOwn) return
            Thread.sleep(20)
        } while (android.os.SystemClock.elapsedRealtime() < deadline)
        error("Carrier override did not complete within the verification deadline")
    }
}
