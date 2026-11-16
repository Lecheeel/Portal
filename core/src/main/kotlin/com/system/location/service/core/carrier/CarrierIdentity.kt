package com.system.location.service.core.carrier

import java.util.Locale
import kotlinx.serialization.Serializable

private val ISO_COUNTRY_CODES = Locale.getISOCountries().toSet()

/** How a caller sees the simulated SIM/operator identity. */
@Serializable
enum class CarrierIdentityMode { GLOBAL, PER_APP }

/** Radio profile exposed to the framework; CDMA/GSM legacy profiles are intentionally absent. */
@Serializable
enum class CarrierRadio { NR, LTE }

/** Which configured SIM a per-app route should expose. */
@Serializable
enum class CarrierTarget { INHERIT, SIM1, SIM2, REAL }

@Serializable
data class SimCarrierProfile(
    val slot: Int,
    val enabled: Boolean = true,
    /** Stored: permit virtual SIM when empty. Resolved snapshot: whether this slot is virtual now. */
    val virtual: Boolean = false,
    val countryIso: String,
    val mcc: Int,
    val mnc: Int,
    val mncDigits: Int = 3,
    val carrierName: String,
    val operatorName: String = carrierName,
    val radio: CarrierRadio = CarrierRadio.NR,
    val nrBand: Int = 78,
    val nrArfcn: Int = CarrierNrChannel.defaultArfcn(nrBand),
    /** Persisted by the controller, not regenerated in injected processes. Zero is legacy input. */
    val identitySeed: Long = 0L,
) {
    init {
        require(CarrierNrChannel.valid(nrBand, nrArfcn)) { "NR band and ARFCN are inconsistent" }
        require(slot in 1..2) { "SIM slot must be 1 or 2" }
        require(countryIso.length == 2 && countryIso.all(Char::isLetter)) {
            "countryIso must be a two-letter ISO code"
        }
        require(countryIso.uppercase(Locale.ROOT) == countryIso) {
            "countryIso must be uppercase"
        }
        require(countryIso in ISO_COUNTRY_CODES) { "Unknown ISO 3166-1 country code" }
        require(mcc in 1..999) { "MCC out of range" }
        require(mnc in 0..999) { "MNC out of range" }
        require(mncDigits in 2..3) { "MNC must have 2 or 3 digits" }
        require(mnc < powerOfTen(mncDigits)) { "MNC does not fit mncDigits" }
        require(carrierName.isNotBlank() && carrierName.toByteArray(Charsets.UTF_8).size <= 128) {
            "carrierName must be 1..128 UTF-8 bytes"
        }
        require(operatorName.isNotBlank() && operatorName.toByteArray(Charsets.UTF_8).size <= 128) {
            "operatorName must be 1..128 UTF-8 bytes"
        }
    }

    val operatorNumeric: String
        get() = mcc.toString().padStart(3, '0') + mnc.toString().padStart(mncDigits, '0')

    private fun powerOfTen(digits: Int): Int = if (digits == 2) 100 else 1_000
}

@Serializable
data class CarrierAppRoute(
    val packageName: String,
    val target: CarrierTarget = CarrierTarget.INHERIT,
) {
    init {
        require(packageName.isNotBlank() && packageName.length <= 255) {
            "packageName must be 1..255 characters"
        }
    }
}

/**
 * Per-scenario SIM/operator identity. The master enable switch is stored separately so turning the
 * feature off leaves the saved profiles intact.
 */
@Serializable
data class CarrierIdentityConfig(
    val mode: CarrierIdentityMode = CarrierIdentityMode.GLOBAL,
    val sims: List<SimCarrierProfile> = emptyList(),
    val appRoutes: List<CarrierAppRoute> = emptyList(),
) {
    init {
        require(sims.size <= 2) { "At most two SIM profiles are supported" }
        require(sims.map { it.slot }.distinct().size == sims.size) { "Duplicate SIM slot" }
        require(appRoutes.size <= MAX_ROUTES) { "Too many carrier routes" }
        val packages = appRoutes.map { it.packageName.lowercase(Locale.ROOT) }
        require(packages.distinct().size == packages.size) { "Duplicate carrier package route" }
    }

    fun profileForSlot(slot: Int): SimCarrierProfile? = sims.firstOrNull { it.slot == slot && it.enabled }

    fun profileForTarget(target: CarrierTarget): SimCarrierProfile? = when (target) {
        CarrierTarget.SIM1 -> profileForSlot(1)
        CarrierTarget.SIM2 -> profileForSlot(2)
        CarrierTarget.INHERIT, CarrierTarget.REAL -> null
    }

    /**
     * Resolve a caller-facing route without turning the system phone stack synthetic in PER_APP
     * mode. GLOBAL is deliberately the default and applies to all callers.
     */
    fun targetFor(packageName: String?, isSystemApp: Boolean): CarrierTarget {
        if (mode == CarrierIdentityMode.GLOBAL) return CarrierTarget.INHERIT
        if (isSystemApp) return CarrierTarget.REAL
        val normalized = packageName?.lowercase(Locale.ROOT) ?: return CarrierTarget.REAL
        return appRoutes.firstOrNull { it.packageName.lowercase(Locale.ROOT) == normalized }?.target
            ?: CarrierTarget.REAL
    }

    companion object {
        const val MAX_ROUTES = 1_024
        val EMPTY = CarrierIdentityConfig()
    }
}

/** Displayable country metadata; every ISO 3166-1 alpha-2 code is selectable. */
data class CarrierCountry(
    val iso: String,
    val displayName: String,
    val mcc: Int?,
)

object CarrierCountries {
    private val knownMcc = mapOf(
        "CN" to 460,
        "HK" to 454,
        "MO" to 455,
        "TW" to 466,
        "US" to 310,
        "GB" to 234,
        "JP" to 440,
        "KR" to 450,
        "SG" to 525,
        "MY" to 502,
        "TH" to 520,
        "VN" to 452,
        "ID" to 510,
        "PH" to 515,
        "IN" to 404,
        "AU" to 505,
        "CA" to 302,
        "DE" to 262,
        "FR" to 208,
        "IT" to 222,
        "ES" to 214,
        "RU" to 250,
        "BR" to 724,
        "MX" to 334,
        "ZA" to 655,
    )

    val all: List<CarrierCountry> by lazy {
        val display = Locale.getDefault()
        Locale.getISOCountries()
            .map { iso ->
                CarrierCountry(
                    iso = iso,
                    // The receiver is the target country; the argument is the display language.
                    displayName = Locale("", iso).getDisplayCountry(display)
                        .takeIf { it.isNotBlank() && it != iso }
                        ?: iso,
                    mcc = knownMcc[iso],
                )
            }
            .sortedWith(compareBy({ it.displayName.lowercase(display) }, { it.iso }))
    }

    fun byIso(iso: String): CarrierCountry? =
        all.firstOrNull { it.iso.equals(iso, ignoreCase = true) }

    fun isValid(iso: String): Boolean = all.any { it.iso.equals(iso, ignoreCase = true) }

    fun defaultMcc(iso: String): Int? = byIso(iso)?.mcc
}

/** A static, best-effort preset; every field remains editable on the carrier page. */
data class CarrierPreset(
    val countryIso: String,
    val mcc: Int,
    val mnc: Int,
    val mncDigits: Int,
    val carrierName: String,
    val operatorName: String = carrierName,
    val radio: CarrierRadio = CarrierRadio.NR,
)

object CarrierPresetCatalog {
    private val presets = listOf(
        CarrierPreset("CN", 460, 0, 2, "中国移动", "China Mobile"),
        CarrierPreset("CN", 460, 1, 2, "中国联通", "China Unicom"),
        CarrierPreset("CN", 460, 3, 2, "中国电信", "China Telecom"),
        CarrierPreset("CN", 460, 11, 2, "中国电信", "China Telecom"),
        CarrierPreset("HK", 454, 0, 2, "CSL", "CSL"),
        CarrierPreset("HK", 454, 1, 2, "中国移动香港", "CMHK"),
        CarrierPreset("HK", 454, 3, 2, "3香港", "3HK"),
        CarrierPreset("HK", 454, 6, 2, "SmarTone", "SmarTone"),
        CarrierPreset("MO", 455, 0, 2, "澳门电讯", "CTM"),
        CarrierPreset("MO", 455, 3, 2, "3澳门", "3 Macau"),
        CarrierPreset("TW", 466, 92, 2, "中华电信", "Chunghwa Telecom"),
        CarrierPreset("TW", 466, 97, 2, "台湾大哥大", "Taiwan Mobile"),
        CarrierPreset("TW", 466, 99, 2, "远传电信", "FarEasTone"),
        CarrierPreset("JP", 440, 10, 2, "NTT docomo", "NTT docomo"),
        CarrierPreset("JP", 440, 20, 2, "au", "au by KDDI"),
        CarrierPreset("JP", 440, 50, 2, "SoftBank", "SoftBank"),
        CarrierPreset("KR", 450, 5, 2, "SK Telecom", "SK Telecom"),
        CarrierPreset("KR", 450, 8, 2, "KT", "KT Corporation"),
        CarrierPreset("KR", 450, 6, 2, "LG U+", "LG U+"),
        CarrierPreset("US", 310, 260, 3, "T-Mobile", "T-Mobile USA"),
        CarrierPreset("US", 310, 410, 3, "AT&T", "AT&T"),
        CarrierPreset("US", 311, 480, 3, "Verizon", "Verizon"),
        CarrierPreset("GB", 234, 15, 2, "Vodafone UK", "Vodafone UK"),
        CarrierPreset("GB", 234, 30, 2, "EE", "EE"),
        CarrierPreset("GB", 234, 10, 2, "O2 UK", "O2 UK"),
        CarrierPreset("GB", 234, 20, 2, "Three UK", "Three UK"),
        CarrierPreset("DE", 262, 1, 2, "Telekom", "T-Mobile DE"),
        CarrierPreset("FR", 208, 1, 2, "Orange FR", "Orange FR"),
        CarrierPreset("AU", 505, 1, 2, "Telstra", "Telstra"),
        CarrierPreset("CA", 302, 220, 3, "Telus", "Telus Mobility"),
        CarrierPreset("IN", 404, 11, 2, "Airtel", "Bharti Airtel"),
        CarrierPreset("BR", 724, 6, 2, "Vivo", "Vivo"),
    )

    fun forCountry(countryIso: String): List<CarrierPreset> =
        presets.filter { it.countryIso.equals(countryIso, ignoreCase = true) }
}
