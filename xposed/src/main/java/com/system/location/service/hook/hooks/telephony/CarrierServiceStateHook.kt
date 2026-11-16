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
            val profile = CarrierIdentityRuntime.profileForCall(chain.executable.name, chain.args)
            val state = original as? ServiceState
            if (state == null) {
                return@Hooker if (profile?.virtual == true) virtualState(profile)
                else original
            }
            when {
                profile != null -> copyWithIdentity(state, profile)
                CarrierIdentityRuntime.protectsLocation(android.os.Binder.getCallingUid()) -> copyWithoutCellIdentity(state)
                else -> original
            }
        }
        listOf("getServiceStateForSubscriber", "getServiceState").forEach { type.hookAllMethods(it, hook) }
    }

    fun copyWithoutCellIdentity(original: ServiceState): ServiceState = runCatching {
        ServiceState(original).apply {
            @Suppress("UNCHECKED_CAST")
            val registrations = Reflection.getObjectField(this, "mNetworkRegistrationInfos") as List<Any>
            registrations.forEach { Reflection.setObjectField(it, "mCellIdentity", null) }
        }
    }.getOrElse {
        ServiceState().apply {
            state = original.state
            roaming = original.roaming
            setOperatorName(original.operatorAlphaLong, original.operatorAlphaShort, original.operatorNumeric)
        }
    }

    fun virtualState(profile: SimCarrierProfile): ServiceState = ServiceState().apply {
        val builder = Reflection.newInstance(Class.forName("android.telephony.NetworkRegistrationInfo\$Builder"))
        Reflection.callMethod(builder, "setDomain", 2) // DOMAIN_PS
        Reflection.callMethod(builder, "setTransportType", 1) // TRANSPORT_TYPE_WWAN
        Reflection.callMethod(builder, "setRegistrationState", 1) // REGISTRATION_STATE_HOME
        Reflection.callMethod(builder, "setAccessNetworkTechnology",
            if (profile.radio == com.system.location.service.core.carrier.CarrierRadio.NR)
                android.telephony.TelephonyManager.NETWORK_TYPE_NR
            else android.telephony.TelephonyManager.NETWORK_TYPE_LTE)
        Reflection.callMethod(builder, "setRegisteredPlmn", profile.operatorNumeric)
        Reflection.callMethod(builder, "setEmergencyOnly", false)
        Reflection.callMethod(builder, "setAvailableServices", listOf(2)) // SERVICE_TYPE_DATA
        val registration = Reflection.callMethod(builder, "build")!!
        Reflection.callMethod(this, "addNetworkRegistrationInfo", registration)
        state = ServiceState.STATE_IN_SERVICE
        Reflection.callMethod(this, "setDataRegState", ServiceState.STATE_IN_SERVICE)
        Reflection.callMethod(this, "setEmergencyOnly", false)
        roaming = false
        setOperatorName(profile.operatorName, profile.carrierName, profile.operatorNumeric)
    }

    fun copyWithIdentity(original: ServiceState, profile: SimCarrierProfile): ServiceState {
        // Never copy absent/emergency modem registrations into a virtual subscriber. Both the
        // query and callback must use the same modeled PS registration and omit real CellIdentity.
        if (profile.virtual) return virtualState(profile)
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
