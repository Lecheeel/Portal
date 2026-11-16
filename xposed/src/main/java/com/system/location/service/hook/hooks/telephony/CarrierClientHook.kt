package com.system.location.service.hook.hooks.telephony

import android.location.LocationManager
import android.os.Bundle
import android.telephony.TelephonyManager
import com.system.location.service.core.carrier.CarrierRadio
import com.system.location.service.core.carrier.SimCarrierProfile
import com.system.location.service.hook.runtime.Reflection
import com.system.location.service.hook.scope.HookInstaller
import com.system.location.service.hook.utils.BinderUtils
import io.github.libxposed.api.XposedInterface.Hooker
import kotlinx.serialization.json.Json
import java.util.Locale

/** Covers public getters backed by local TelephonyProperties rather than PhoneInterfaceManager. */
object CarrierClientHook {
    private val reading = ThreadLocal.withInitial { false }
    fun install(loader: ClassLoader) {
        val type = Reflection.findClassIfExists("android.telephony.TelephonyManager", loader) ?: return
        val numeric = setOf("getSimOperator", "getSimOperatorNumeric", "getSimOperatorNumericForPhone",
            "getNetworkOperator", "getNetworkOperatorForPhone")
        val simName = setOf("getSimOperatorName", "getSimOperatorNameForPhone")
        val networkName = setOf("getNetworkOperatorName")
        val country = setOf("getSimCountryIso", "getSimCountryIsoForPhone", "getNetworkCountryIso",
            "getNetworkCountryIsoForPhone")
        val network = setOf("getDataNetworkType", "getNetworkType")
        val phone = setOf("getPhoneType")
        val hook = Hooker { chain ->
            if (reading.get() == true) return@Hooker chain.proceed()
            reading.set(true)
            try {
                // Preserve original permission checks, exceptions and missing-information shape.
                val original = chain.proceed()
                if (original == null || original is String && original.isEmpty()) return@Hooker original
                val name = chain.executable.name
                if (original == 0 && (name in network || name in phone)) return@Hooker original
                val requested = chain.args.firstOrNull() as? Int
                val manager = chain.thisObject as? TelephonyManager
                val phoneId = name.endsWith("ForPhone") || name == "getNetworkCountryIso" && requested != null
                val id = requested ?: manager?.subscriptionId ?: return@Hooker original
                val profile = snapshot(if (phoneId) "phone" else "subscription", id) ?: return@Hooker original
                when (name) {
                    in numeric -> profile.operatorNumeric
                    in simName -> profile.carrierName
                    in networkName -> profile.operatorName
                    in country -> profile.countryIso.lowercase(Locale.ROOT)
                    in network -> if (profile.radio == CarrierRadio.NR) TelephonyManager.NETWORK_TYPE_NR
                        else TelephonyManager.NETWORK_TYPE_LTE
                    in phone -> TelephonyManager.PHONE_TYPE_GSM
                    else -> original
                }
            } finally { reading.set(false) }
        }
        (numeric + simName + networkName + country + network + phone).forEach {
            HookInstaller.hookAllMethods(type, it, hook)
        }
    }

    private fun snapshot(kind: String, id: Int): SimCarrierProfile? = runCatching {
        val context = BinderUtils.getSystemContext() ?: return null
        val location = context.getSystemService(LocationManager::class.java) ?: return null
        val reply = Bundle().apply { putString("kind", kind); putInt("id", id) }
        if (!location.sendExtraCommand("fused_ext", CarrierIdentitySnapshotCommand.COMMAND, reply)) return null
        val encoded = reply.getString(CarrierIdentitySnapshotCommand.PROFILE) ?: return null
        if (encoded.length > 4096) return null
        Json.decodeFromString(SimCarrierProfile.serializer(), encoded)
    }.getOrNull()
}
