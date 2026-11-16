package com.system.location.service.core.carrier

import kotlin.math.absoluteValue

/**
 * Stable, checksum-valid identifiers for a virtual SIM. These are modeled values, not modem
 * identifiers: the same slot/MCC/MNC/serving profile produces the same IMSI/ICCID across processes
 * and releases so the app, SystemUI and the phone process cannot disagree.
 */
object CarrierVirtualIdentity {
    const val VIRTUAL_SUB_ID_BASE = 900_000_000
    const val UNKNOWN_CARRIER_ID = -1

    fun subscriptionId(slot: Int): Int {
        require(slot in 1..2) { "SIM slot must be 1 or 2" }
        return VIRTUAL_SUB_ID_BASE + slot
    }

    fun slotForSubscriptionId(subId: Int): Int? =
        (subId - VIRTUAL_SUB_ID_BASE).takeIf { it in 1..2 }

    /**
     * Stable positive carrier id. Real devices use -1 for "unknown"; a named virtual carrier with
     * an unknown id is internally inconsistent and is a detectable fingerprint.
     */
    fun carrierId(profile: SimCarrierProfile): Int =
        1_000 + ((profile.mcc * 31 + profile.mnc * 17 + profile.slot) % 8_000)

    /** 20-digit ICCID, including the Luhn check digit. */
    fun iccid(profile: SimCarrierProfile): String {
        val body = buildString {
            append("8944")
            append(profile.mcc.toString().padStart(3, '0'))
            append(profile.mnc.toString().padStart(profile.mncDigits, '0'))
            append((profile.slot - 1).coerceIn(0, 1))
            append((profile.countryIso.hashCode().absoluteValue % 100_000_000).toString().padStart(8, '0'))
        }.take(19).padEnd(19, '0')
        return body + luhnCheckDigit(body)
    }

    /** 15-digit IMSI: MCC + MNC + deterministic subscriber digits. */
    fun imsi(profile: SimCarrierProfile): String {
        val subscriberDigits = 15 - 3 - profile.mncDigits
        val body = buildString {
            append(profile.mcc.toString().padStart(3, '0'))
            append(profile.mnc.toString().padStart(profile.mncDigits, '0'))
            append((profile.slot - 1).coerceIn(0, 1))
            append((profile.countryIso.hashCode().absoluteValue % 1_000_000_000).toString()
                .padStart(9, '0'))
        }.take(3 + profile.mncDigits + subscriberDigits)
            .padEnd(3 + profile.mncDigits + subscriberDigits, '0')
        return body
    }

    private fun luhnCheckDigit(body: String): Char {
        var sum = 0
        for ((index, char) in body.reversed().withIndex()) {
            var digit = char.digitToIntOrNull() ?: 0
            if (index % 2 == 0) {
                digit *= 2
                if (digit > 9) digit -= 9
            }
            sum += digit
        }
        return ((10 - sum % 10) % 10).digitToChar()
    }
}
