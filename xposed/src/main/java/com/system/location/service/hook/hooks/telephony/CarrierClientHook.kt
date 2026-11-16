package com.system.location.service.hook.hooks.telephony

import android.location.LocationManager
import android.os.Bundle
import android.telephony.TelephonyManager
import com.system.location.service.core.carrier.CarrierRadio
import com.system.location.service.core.carrier.CarrierVirtualIdentity
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
        val network = setOf("getDataNetworkType", "getNetworkType", "getVoiceNetworkType")
        val phone = setOf("getPhoneType")
        val simState = setOf("getSimState")
        val subscriber = setOf("getSubscriberId")
        val iccid = setOf("getIccId", "getSimSerialNumber")
        val carrierId = setOf("getSimCarrierId", "getSimCarrierIdName", "getSimSpecificCarrierId",
            "getSimSpecificCarrierIdName")
        val hook = Hooker { chain ->
            if (reading.get() == true) return@Hooker chain.proceed()
            reading.set(true)
            try {
                // Preserve original permission checks and exceptions. A virtual subscription may
                // legitimately replace an empty/absent platform result.
                val original = chain.proceed()
                val name = chain.executable.name
                val requested = chain.args.firstOrNull() as? Int
                val manager = chain.thisObject as? TelephonyManager
                val phoneId = name.endsWith("ForPhone") || name == "getNetworkCountryIso" && requested != null
                val id = requested ?: manager?.subscriptionId ?: -1
                val profile = snapshot(if (phoneId) "phone" else "subscription", id)
                    ?: return@Hooker original
                if (!profile.virtual) {
                    if (original == null || original is String && original.isEmpty()) return@Hooker original
                    if (original == 0 && (name in network || name in phone || name in simState)) {
                        return@Hooker original
                    }
                }
                when (name) {
                    in numeric -> profile.operatorNumeric
                    in simName -> profile.carrierName
                    in networkName -> profile.operatorName
                    in country -> profile.countryIso.lowercase(Locale.ROOT)
                    in network -> if (profile.radio == CarrierRadio.NR) TelephonyManager.NETWORK_TYPE_NR
                        else TelephonyManager.NETWORK_TYPE_LTE
                    in phone -> TelephonyManager.PHONE_TYPE_GSM
                    in simState -> if (profile.virtual) TelephonyManager.SIM_STATE_READY else original
                    in subscriber -> if (profile.virtual) CarrierVirtualIdentity.imsi(profile) else original
                    in iccid -> if (profile.virtual) CarrierVirtualIdentity.iccid(profile) else original
                    in carrierId -> if (profile.virtual) {
                        when (name) {
                            "getSimCarrierId", "getSimSpecificCarrierId" ->
                                CarrierVirtualIdentity.UNKNOWN_CARRIER_ID
                            else -> profile.carrierName
                        }
                    } else original
                    else -> original
                }
            } finally { reading.set(false) }
        }
        (numeric + simName + networkName + country + network + phone + simState + subscriber +
            iccid + carrierId).forEach {
            HookInstaller.hookAllMethods(type, it, hook)
        }
        installSubscriptionManager(loader)
    }

    /**
     * MIUI's plugin controller reads SubId/SimState through static SubscriptionManager helpers.
     * These calls never enter PhoneInterfaceManager, so they need their own overlay.
     */
    private fun installSubscriptionManager(loader: ClassLoader) {
        val type = Reflection.findClassIfExists("android.telephony.SubscriptionManager", loader) ?: return
        val hook = Hooker { chain ->
            if (reading.get() == true) return@Hooker chain.proceed()
            reading.set(true)
            try {
                val original = chain.proceed()
                val name = chain.executable.name
                val requestedSlot = (chain.args.firstOrNull() as? Int)?.plus(1) ?: 1
                val profile = virtualForSlot(requestedSlot) ?: return@Hooker original
                when (name) {
                    "getSubId" -> intArrayOf(CarrierVirtualIdentity.subscriptionId(profile.slot))
                    "getDefaultSubscriptionId", "getDefaultDataSubscriptionId",
                    "getDefaultVoiceSubscriptionId", "getDefaultSmsSubscriptionId",
                    "getDefaultSubscriptionIdForPhone" ->
                        CarrierVirtualIdentity.subscriptionId(profile.slot)
                    else -> original
                }
            } finally { reading.set(false) }
        }
        listOf("getSubId", "getDefaultSubscriptionId", "getDefaultDataSubscriptionId",
            "getDefaultVoiceSubscriptionId", "getDefaultSmsSubscriptionId",
            "getDefaultSubscriptionIdForPhone").forEach {
            HookInstaller.hookAllMethods(type, it, hook)
        }
    }

    internal fun statusBarProfile(): SimCarrierProfile? {
        val ids = listOf(CarrierVirtualIdentity.subscriptionId(1),
            CarrierVirtualIdentity.subscriptionId(2), -1)
        return ids.firstNotNullOfOrNull { id -> snapshot("subscription", id) }
    }

    private fun virtualForSlot(slot: Int): SimCarrierProfile? {
        val profile = statusBarProfile() ?: return null
        return profile.takeIf { it.slot == slot && it.virtual }
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
