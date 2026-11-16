package com.system.location.service.hook.hooks.telephony

import android.os.PersistableBundle
import com.system.location.service.hook.runtime.Reflection
import com.system.location.service.hook.utils.hookAllMethods
import io.github.libxposed.api.XposedInterface.Hooker
import java.util.Locale

/** PER_APP identity overlay plus internal status handshake; GLOBAL uses the real temporary layer. */
internal object CarrierConfigReadHook {
    fun install(loader: ClassLoader) {
        val type = Reflection.findClassIfExists("com.android.phone.CarrierConfigLoader", loader) ?: return
        val hook = Hooker { chain ->
            val original = chain.proceed()
            val bundle = original as? PersistableBundle ?: return@Hooker original
            if (chain.args.getOrNull(2) == CarrierOverrideProtocol.FEATURE &&
                android.os.Binder.getCallingUid() == 1000) {
                val status = CarrierConfigOverrideHook.status(chain.args.firstOrNull() as? Int ?: -1)
                return@Hooker PersistableBundle().apply {
                    putBoolean(CarrierOverrideProtocol.SUPPORTED, CarrierConfigOverrideHook.supported)
                    putBoolean(CarrierOverrideProtocol.OWNED, status.first)
                    putBoolean(CarrierOverrideProtocol.PENDING, status.second)
                    putBoolean(CarrierOverrideProtocol.FAILED,
                        CarrierConfigOverrideHook.failed(chain.args.firstOrNull() as? Int ?: -1))
                }
            }
            // AOSP returns an empty bundle when READ_PHONE_STATE is denied. Keep that
            // redaction intact; profile availability is not authorization to expose new data.
            if (bundle.isEmpty) return@Hooker original
            val mode = com.system.location.service.hook.utils.FakeLoc.runtimeSnapshots.current()
                .configuration.carrierIdentity?.mode
            val profile = CarrierIdentityRuntime.profileForCall(chain.executable.name, chain.args)
                ?: return@Hooker original
            // GLOBAL physical SIMs already receive the real temporary CarrierConfig layer.
            // Virtual slots (including no-SIM topology) need a caller-scoped read overlay.
            if (mode == com.system.location.service.core.carrier.CarrierIdentityMode.GLOBAL &&
                !profile.virtual) return@Hooker original
            val subset = chain.executable.name == "getConfigSubsetForSubIdWithFeature"
            val requested = chain.args.filterIsInstance<Array<*>>().flatMap { it.filterIsInstance<String>() }.toSet()
            overlay(bundle, profile, if (subset) requested else null)
        }
        listOf("getConfigForSubId", "getConfigForSubIdWithFeature", "getConfigSubsetForSubIdWithFeature")
            .forEach { name -> type.hookAllMethods(name, hook) }
    }

    internal fun overlay(bundle: PersistableBundle,
        profile: com.system.location.service.core.carrier.SimCarrierProfile,
        requested: Set<String>?): PersistableBundle {
        if (bundle.isEmpty) return bundle
        return PersistableBundle(bundle).apply {
            fun includes(key: String) = requested == null || key in requested
            if (includes("sim_country_iso_override_string")) {
                putString("sim_country_iso_override_string", profile.countryIso.lowercase(Locale.ROOT))
            }
            if (includes("carrier_name_override_bool")) putBoolean("carrier_name_override_bool", true)
            if (includes("carrier_name_string")) putString("carrier_name_string", profile.carrierName)
            if (profile.virtual && includes("carrier_id_int")) {
                putInt("carrier_id_int", com.system.location.service.core.carrier.CarrierVirtualIdentity.carrierId(profile))
            }
        }
    }
}
