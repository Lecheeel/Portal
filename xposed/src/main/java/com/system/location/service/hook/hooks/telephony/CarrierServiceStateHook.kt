package com.system.location.service.hook.hooks.telephony

import android.telephony.ServiceState
import com.system.location.service.core.carrier.SimCarrierProfile
import com.system.location.service.hook.runtime.Reflection
import com.system.location.service.hook.utils.hookAllMethods
import io.github.libxposed.api.XposedInterface.Hooker

/** Keep the original registration, emergency, roaming and transport state in a caller-local copy. */
object CarrierServiceStateHook {
    fun install(loader: ClassLoader) {
        val type = Reflection.findClassIfExists("com.android.phone.PhoneInterfaceManager", loader) ?: return
        val hook = Hooker { chain ->
            val original = chain.proceed()
            val state = original as? ServiceState ?: return@Hooker original
            val profile = CarrierIdentityRuntime.profileForCall(chain.executable.name, chain.args)
                ?: return@Hooker original
            copyWithIdentity(state, profile)
        }
        listOf("getServiceStateForSubscriber", "getServiceState").forEach { type.hookAllMethods(it, hook) }
    }

    fun copyWithIdentity(original: ServiceState, profile: SimCarrierProfile): ServiceState {
        val copy = runCatching {
            ServiceState(original).apply {
                @Suppress("UNCHECKED_CAST")
                val registrations = Reflection.getObjectField(this, "mNetworkRegistrationInfos") as List<Any>
                registrations.forEach { info ->
                    Reflection.setObjectField(info, "mCellIdentity", null)
                    val plmn = Reflection.getObjectField(info, "mRplmn") as? String
                    if (!plmn.isNullOrEmpty()) Reflection.setObjectField(info, "mRplmn", profile.operatorNumeric)
                    val registered = Reflection.callMethod(info, "isRegistered") == true
                    val domain = Reflection.callMethod(info, "getDomain") as? Int
                    val transport = Reflection.callMethod(info, "getTransportType") as? Int
                    if (registered && domain == 2 && transport == 1) {
                        Reflection.callMethod(info, "setAccessNetworkTechnology",
                            if (profile.radio == com.system.location.service.core.carrier.CarrierRadio.NR)
                                android.telephony.TelephonyManager.NETWORK_TYPE_NR
                            else android.telephony.TelephonyManager.NETWORK_TYPE_LTE)
                    }
                }
                listOf("mOperatorAlphaLongRaw" to profile.operatorName,
                    "mOperatorAlphaShortRaw" to profile.carrierName).forEach { (fieldName, value) ->
                    val field = Reflection.findFieldIfExists(javaClass, fieldName)
                    if (!(field?.get(this) as? String).isNullOrEmpty()) field?.set(this, value)
                }
            }
        }.getOrElse {
            // Unknown OEM layout must not make the hook return a real CellIdentity. Keep only
            // the coarse service/roaming state in an otherwise empty platform object.
            ServiceState().apply { state = original.state; roaming = original.roaming }
        }
        copy.setOperatorName(
            if (original.operatorAlphaLong.isNullOrEmpty()) original.operatorAlphaLong else profile.operatorName,
            if (original.operatorAlphaShort.isNullOrEmpty()) original.operatorAlphaShort else profile.carrierName,
            if (original.operatorNumeric.isNullOrEmpty()) original.operatorNumeric else profile.operatorNumeric,
        )
        return copy
    }
}
