package com.system.location.service.core.carrier

/** Best-effort carrier band hints; a hint is editable and never asserted as live deployment. */
object CarrierNrBandCatalog {
    private val global = listOf(78)
    private val china = listOf(1, 3, 5, 8, 28, 41, 78, 79)
    private val unitedStates = listOf(2, 5, 7, 12, 13, 14, 25, 26, 41, 48, 66, 71, 77, 78)
    private val australia = listOf(1, 3, 5, 8, 28, 40, 41, 78, 79)

    fun commonBands(countryIso: String, mcc: Int, mnc: Int): List<Int> {
        return when (countryIso.uppercase(java.util.Locale.ROOT)) {
            "CN" -> when (mnc) {
                0, 2, 4, 7, 8 -> china
                1, 6, 9 -> listOf(1, 3, 8, 78)
                3, 5, 11 -> listOf(1, 3, 5, 8, 28, 78)
                else -> china
            }
            "US" -> when {
                mcc == 310 && mnc == 260 -> listOf(41, 71, 77, 78)
                mcc == 310 && mnc == 410 -> listOf(2, 5, 12, 14, 25, 26, 77)
                mcc == 311 && mnc == 480 -> listOf(2, 5, 13, 66, 77, 78)
                else -> unitedStates
            }
            "AU" -> australia
            else -> global
        }.filter { it in CarrierNrChannel.bands }
    }

    /** A valid default band for a country/carrier, preferring the catalog head. */
    fun defaultBand(countryIso: String, mcc: Int, mnc: Int): Int =
        commonBands(countryIso, mcc, mnc).firstOrNull() ?: 78
}
