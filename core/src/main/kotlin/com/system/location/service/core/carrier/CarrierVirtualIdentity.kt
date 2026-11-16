package com.system.location.service.core.carrier

/** Stable modeled identifiers derived from a controller-persisted seed and slot/PLMN.
 * A valid checksum and format do not imply an assigned issuer or a network credential.
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

    /** Base MNO identities from AOSP carrier_list.textpb (no MVNO GID/SPN matching).
     * Unknown PLMNs retain Android's -1; a positive arbitrary number is not a carrier identity.
     * https://android.googlesource.com/platform/packages/providers/TelephonyProvider/+/refs/heads/main/assets/latest_carrier_id/carrier_list.textpb
     */
    fun carrierId(profile: SimCarrierProfile): Int = when (profile.operatorNumeric) {
        "310260" -> 1 // T-Mobile US
        "310410" -> 1187 // AT&T
        "311480" -> 1839 // Verizon Wireless
        "50501", "50539", "50571", "50572" -> 1345 // Telstra
        "50502", "50590" -> 30 // Optus
        "50503" -> 15 // Vodafone AU
        "46000", "46002", "46004", "46007", "46008" -> 1435
        "46001", "46006", "46009" -> 1436
        "46003", "46011" -> 2237
        else -> UNKNOWN_CARRIER_ID
    }

    /** Modeled E.118-shaped 20-digit ICCID, not an assigned issuer credential. */
    fun iccid(profile: SimCarrierProfile): String {
        // Country calling code, not MCC. Unknown issuers use a neutral modeled prefix rather
        // than claiming every SIM was issued in the United Kingdom (the old fixed 8944).
        val callingCode = when (profile.countryIso) {
            "CN" -> "86"; "US", "CA" -> "1"; "AU" -> "61"
            "GB" -> "44"; "DE" -> "49"; "FR" -> "33"
            "JP" -> "81"; "KR" -> "82"; "HK" -> "852"; "TW" -> "886"
            "SG" -> "65"; "NZ" -> "64"; else -> "00"
        }
        val prefix = "89$callingCode"
        val body = prefix + digits(profile, "iccid", 19 - prefix.length)
        return body + luhnCheckDigit(body)
    }

    /** MCC/MNC plus stable per-profile subscriber digits; names and radio edits preserve identity. */
    fun imsi(profile: SimCarrierProfile): String =
        profile.operatorNumeric + digits(profile, "imsi", 15 - profile.operatorNumeric.length)

    private fun digits(profile: SimCarrierProfile, kind: String, count: Int): String {
        val input = "$kind:${profile.identitySeed}:${profile.slot}:${profile.operatorNumeric}"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return digest.take(count).joinToString("") { ((it.toInt() and 255) % 10).toString() }
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
