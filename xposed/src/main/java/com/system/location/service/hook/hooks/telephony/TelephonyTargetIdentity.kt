package com.system.location.service.hook.hooks.telephony

import android.telephony.TelephonyManager
import com.system.location.service.core.environment.CellType
import com.system.location.service.core.environment.EnvironmentProfile

internal data class TelephonyTargetIdentity(
    val operatorNumeric: String?,
    val operatorName: String?,
    val phoneType: Int,
    val networkType: Int,
)

internal fun telephonyTargetIdentity(profile: EnvironmentProfile?): TelephonyTargetIdentity? {
    profile ?: return null
    val registeredCell = profile.registeredCell()
    if (registeredCell != null) {
        val networkType = when (registeredCell.type) {
            CellType.GSM -> TelephonyManager.NETWORK_TYPE_UNKNOWN
            CellType.WCDMA -> TelephonyManager.NETWORK_TYPE_UMTS
            CellType.LTE -> TelephonyManager.NETWORK_TYPE_LTE
            CellType.NR -> TelephonyManager.NETWORK_TYPE_NR
        }
        return TelephonyTargetIdentity(
            operatorNumeric = registeredCell.operatorNumeric,
            operatorName = registeredCell.operatorName,
            phoneType = TelephonyManager.PHONE_TYPE_GSM,
            networkType = networkType,
        )
    }
    if (profile.registeredCdmaCell() != null) {
        return TelephonyTargetIdentity(
            operatorNumeric = null,
            operatorName = null,
            phoneType = TelephonyManager.PHONE_TYPE_CDMA,
            networkType = TelephonyManager.NETWORK_TYPE_CDMA,
        )
    }
    return null
}
