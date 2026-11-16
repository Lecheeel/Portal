package com.system.location.service.hook.hooks.telephony

import android.os.PersistableBundle
import com.system.location.service.hook.runtime.Reflection
import com.system.location.service.hook.utils.hookAllMethods
import io.github.libxposed.api.XposedInterface.Hooker
import java.util.Locale

/** Read-side identity overlay, including PER_APP, without mutating a cached carrier bundle. */
internal object CarrierConfigReadHook {
    fun install(loader: ClassLoader) {
        val type = Reflection.findClassIfExists("com.android.phone.CarrierConfigLoader", loader) ?: return
        val hook = Hooker { chain ->
            val original = chain.proceed()
            val bundle = original as? PersistableBundle ?: return@Hooker original
            val profile = CarrierIdentityRuntime.profileForCall(chain.executable.name, chain.args)
                ?: return@Hooker original
            val subset = chain.executable.name == "getConfigSubsetForSubIdWithFeature"
            val requested = chain.args.filterIsInstance<Array<*>>().flatMap { it.filterIsInstance<String>() }.toSet()
            PersistableBundle(bundle).apply {
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
