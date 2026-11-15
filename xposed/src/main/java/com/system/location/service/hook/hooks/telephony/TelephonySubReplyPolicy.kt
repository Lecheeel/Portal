package com.system.location.service.hook.hooks.telephony

import android.telephony.TelephonyManager

internal enum class TelephonySubOperation {
    PHONE_ID,
    ACTIVE_SUBSCRIPTION,
    SIM_STATE_FOR_SLOT,
    SIM_STATE_FOR_SUBSCRIPTION,
    NETWORK_COUNTRY_ISO,
}

internal fun telephonySubReply(
    operation: TelephonySubOperation,
    requestedId: Int,
    identity: TelephonyTargetIdentity?,
): Any =
    when (operation) {
        TelephonySubOperation.PHONE_ID -> -1
        TelephonySubOperation.ACTIVE_SUBSCRIPTION -> false
        TelephonySubOperation.SIM_STATE_FOR_SLOT -> if (requestedId == 0 && identity != null) {
            TelephonyManager.SIM_STATE_READY
        } else {
            TelephonyManager.SIM_STATE_UNKNOWN
        }
        TelephonySubOperation.SIM_STATE_FOR_SUBSCRIPTION -> TelephonyManager.SIM_STATE_UNKNOWN
        TelephonySubOperation.NETWORK_COUNTRY_ISO -> ""
    }
