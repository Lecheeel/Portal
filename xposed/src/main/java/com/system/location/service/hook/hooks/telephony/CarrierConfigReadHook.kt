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
            if (bundle.isEmpty && chain.args.getOrNull(2) == CarrierOverrideProtocol.FEATURE) {
                return@Hooker original
            }
            val mode = com.system.location.service.hook.utils.FakeLoc.runtimeSnapshots.current()
                .configuration.carrierIdentity?.mode
            val profile = CarrierIdentityRuntime.profileForCall(chain.executable.name, chain.args)
                ?: return@Hooker original
            // GLOBAL physical SIMs already receive the real temporary CarrierConfig layer.
            // Virtual slots (including no-SIM topology) need a caller-scoped read overlay.
            if (mode == com.system.location.service.core.carrier.CarrierIdentityMode.GLOBAL &&
                !profile.virtual) return@Hooker original
            if (bundle.isEmpty && !profile.virtual && chain.args.getOrNull(2) != null) {
                // Preserve the platform redaction shape for real subscriptions.
                return@Hooker original
            }
            val subset = chain.executable.name == "getConfigSubsetForSubIdWithFeature"
            val requested = chain.args.filterIsInstance<Array<*>>().flatMap { it.filterIsInstance<String>() }.toSet()
            (if (bundle.isEmpty) PersistableBundle() else PersistableBundle(bundle)).apply {
                if (!subset || "sim_country_iso_override_string" in requested || containsKey("sim_country_iso_override_string")) {
                    putString("sim_country_iso_override_string", profile.countryIso.lowercase(Locale.ROOT))
                }
                if (!subset || "carrier_name_override_bool" in requested || containsKey("carrier_name_override_bool")) {
                    putBoolean("carrier_name_override_bool", true)
                }
                if (!subset || "carrier_name_string" in requested || containsKey("carrier_name_string")) {
                    putString("carrier_name_string", profile.carrierName)
                }
            }
        }
        listOf("getConfigForSubId", "getConfigForSubIdWithFeature", "getConfigSubsetForSubIdWithFeature")
            .forEach { name -> type.hookAllMethods(name, hook) }
    }
}
