package com.system.location.service.hook.hooks.telephony

import android.os.IBinder
import android.os.PersistableBundle
import android.telephony.SubscriptionManager
import com.system.location.service.core.carrier.CarrierIdentityMode
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

    @Synchronized
    fun onSimulationStopped() {
        if (!FakeLoc.isSystemServerProcess) return
        restoreApplied()
    }

    @Synchronized
    fun onConfigurationChanged(state: RuntimeSnapshot) {
        if (!FakeLoc.isSystemServerProcess) return
        val policy = state.configuration
        val config = policy.carrierIdentity
        val enabled = policy.carrierIdentityEnabled &&
            config != null && config.mode == CarrierIdentityMode.GLOBAL
        if (!enabled) {
            restoreApplied()
            return
        }
        val loader = carrierConfigLoader()
        if (loader == null) {
            Logger.warn("Global carrier identity unavailable: carrier_config service not found")
            return
        }
        val next = LinkedHashSet<Int>()
        for (slot in 1..2) {
            val profile = config?.profileForSlot(slot) ?: continue
            val subIds = subIdsForSlot(slot)
            for (subId in subIds) {
                runCatching {
                    overrideConfig(loader, subId, bundleFor(profile))
                    next += subId
                }.onFailure {
                    Logger.warn("Global carrier identity failed for slot=$slot sub=$subId: ${it.message}")
                }
            }
        }
        val removed = appliedSubIds - next
        for (subId in removed) {
            runCatching { overrideConfig(loader, subId, null) }
        }
        appliedSubIds = next
    }

    private fun restoreApplied() {
        if (appliedSubIds.isEmpty()) return
        val loader = carrierConfigLoader() ?: return
        for (subId in appliedSubIds) {
            runCatching { overrideConfig(loader, subId, null) }
        }
        appliedSubIds = emptySet()
    }

    private fun bundleFor(profile: SimCarrierProfile): PersistableBundle = PersistableBundle().apply {
        putString(COUNTRY, profile.countryIso.lowercase())
        putBoolean(NAME_OVERRIDE, true)
        putString(NAME, profile.carrierName)
        // Matches the AOSP CTS carrier-name path used by Nrfr; it keeps the override active on
        // the home network rather than allowing roaming configuration to undo it.
        putBoolean(HOME, true)
    }

    private fun subIdsForSlot(slot: Int): List<Int> {
        val direct = runCatching {
            val method = SubscriptionManager::class.java.getMethod("getSubId", Int::class.javaPrimitiveType)
            (method.invoke(null, slot - 1) as? IntArray)?.toList().orEmpty()
        }.getOrDefault(emptyList())
        if (direct.isNotEmpty()) return direct.filter { it > 0 }
        // Last-resort path for OEMs that hide the static helper: at least cover the default
        // subscription instead of silently doing nothing.
        val fallback = runCatching {
            val defaultData = SubscriptionManager.getDefaultDataSubscriptionId()
            val defaultVoice = SubscriptionManager.getDefaultVoiceSubscriptionId()
            listOf(defaultData, defaultVoice).filter {
                it != SubscriptionManager.INVALID_SUBSCRIPTION_ID && it > 0
            }
        }.getOrDefault(emptyList())
        return if (slot == 1) fallback else emptyList()
    }

    private fun carrierConfigLoader(): Any? = runCatching {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val binder = serviceManager.getMethod("getService", String::class.java)
            .invoke(null, "carrier_config") as? IBinder ?: return null
        val stub = Class.forName("com.android.internal.telephony.ICarrierConfigLoader\$Stub")
        stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
    }.getOrNull()

    private fun overrideConfig(loader: Any, subId: Int, bundle: PersistableBundle?) {
        val method = loader.javaClass.methods.firstOrNull {
            it.name == "overrideConfig" &&
                it.parameterTypes.toList() == listOf(
                    Int::class.javaPrimitiveType,
                    PersistableBundle::class.java,
                    Boolean::class.javaPrimitiveType,
                )
        } ?: error("overrideConfig signature missing")
        method.invoke(loader, subId, bundle, false)
    }
}
