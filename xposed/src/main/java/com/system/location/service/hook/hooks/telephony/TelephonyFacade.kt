package com.system.location.service.hook.hooks.telephony

import android.telephony.TelephonyManager

/**
 * Device-level telephony facts read from the platform properties that the phone process already
 * maintains. The hook layer must mirror these; inventing a fixed 460/0 profile is a cross-checkable
 * fingerprint on every non-China-Mobile device (and on devices without a SIM).
 */
internal object TelephonyFacade {
    data class Sim(
        val mcc: Int,
        val mnc: Int,
        val ready: Boolean,
        val numeric: String,
        val name: String,
    )

    fun sim(): Sim {
        val numeric = firstNonBlank(
            property("gsm.sim.operator.numeric"),
            property("gsm.operator.numeric"),
            property("ro.cdma.home.operator.numeric"),
        )
        val state = property("gsm.sim.state").uppercase()
        val ready = state in setOf("READY", "LOADED") && numeric.isNotBlank()
        val parsed = parseNumeric(numeric)
        return Sim(
            mcc = parsed?.first ?: 460,
            mnc = parsed?.second ?: 0,
            ready = ready,
            numeric = numeric,
            name = firstNonBlank(
                property("gsm.sim.operator.alpha"),
                property("gsm.operator.alpha"),
                carrierName(parsed?.first, parsed?.second),
            ),
        )
    }

    fun simNumeric(): String? = sim().takeIf { it.ready }?.numeric

    fun simName(): String? = sim().takeIf { it.ready }?.name?.takeIf { it.isNotBlank() }

    /** Best-effort mapping of the modem's current data RAT, not a hard-coded LTE. */
    fun networkType(): Int {
        val raw = property("gsm.network.type").substringBefore(',').trim().uppercase()
        return when (raw) {
            "GPRS" -> TelephonyManager.NETWORK_TYPE_GPRS
            "EDGE" -> TelephonyManager.NETWORK_TYPE_EDGE
            "UMTS" -> TelephonyManager.NETWORK_TYPE_UMTS
            "CDMA" -> TelephonyManager.NETWORK_TYPE_CDMA
            "1XRTT" -> TelephonyManager.NETWORK_TYPE_1xRTT
            "EVDO_0" -> TelephonyManager.NETWORK_TYPE_EVDO_0
            "EVDO_A" -> TelephonyManager.NETWORK_TYPE_EVDO_A
            "EVDO_B" -> TelephonyManager.NETWORK_TYPE_EVDO_B
            "HSDPA" -> TelephonyManager.NETWORK_TYPE_HSDPA
            "HSUPA" -> TelephonyManager.NETWORK_TYPE_HSUPA
            "HSPA" -> TelephonyManager.NETWORK_TYPE_HSPA
            "HSPAP" -> TelephonyManager.NETWORK_TYPE_HSPAP
            "LTE" -> TelephonyManager.NETWORK_TYPE_LTE
            "NR" -> TelephonyManager.NETWORK_TYPE_NR
            "IWLAN" -> TelephonyManager.NETWORK_TYPE_IWLAN
            else -> TelephonyManager.NETWORK_TYPE_UNKNOWN
        }
    }

    fun phoneType(): Int = when {
        !sim().ready -> TelephonyManager.PHONE_TYPE_NONE
        property("ro.cdma.home.operator.numeric").isNotBlank() -> TelephonyManager.PHONE_TYPE_CDMA
        else -> TelephonyManager.PHONE_TYPE_GSM
    }

    fun simState(): Int = when {
        sim().ready -> TelephonyManager.SIM_STATE_READY
        property("gsm.sim.state").uppercase() == "ABSENT" -> TelephonyManager.SIM_STATE_ABSENT
        property("gsm.sim.state").isBlank() -> TelephonyManager.SIM_STATE_UNKNOWN
        else -> TelephonyManager.SIM_STATE_NOT_READY
    }

    fun countryIso(): String = firstNonBlank(
        property("gsm.sim.operator.iso-country"),
        property("gsm.operator.iso-country"),
    )

    private fun parseNumeric(numeric: String): Pair<Int, Int>? {
        if (numeric.length !in 5..6 || !numeric.all(Char::isDigit)) return null
        return numeric.substring(0, 3).toInt() to numeric.substring(3).toInt()
    }

    private fun carrierName(mcc: Int?, mnc: Int?): String = when (mcc to mnc) {
        460 to 0 -> "中国移动"
        460 to 1, 460 to 6 -> "中国联通"
        460 to 3, 460 to 5, 460 to 11 -> "中国电信"
        else -> ""
    }

    private fun firstNonBlank(vararg values: String?): String =
        values.firstOrNull { !it.isNullOrBlank() }.orEmpty()

    private fun property(key: String): String = runCatching {
        systemPropertiesClass?.getMethod("get", String::class.java)?.invoke(null, key) as? String
    }.getOrNull().orEmpty()

    private val systemPropertiesClass: Class<*>? by lazy {
        runCatching { Class.forName("android.os.SystemProperties") }.getOrNull()
    }
}
