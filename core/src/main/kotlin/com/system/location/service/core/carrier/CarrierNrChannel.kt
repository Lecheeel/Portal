package com.system.location.service.core.carrier

/** Supported FR1 channel rasters for the synthetic receiver. Frequencies are not live scans. */
object CarrierNrChannel {
    // Common commercial FR1 bands in CN (41/78/79), US (2/5/7/12/13/14/25/26/41/48/66/71/77/78)
    // and AU (1/3/5/8/28/40/41/78/79), plus a few roaming bands.
    private val ranges = mapOf(
        1 to (422000..434000),
        2 to (386000..398000),
        3 to (361000..376000),
        5 to (173800..178800),
        7 to (524000..538000),
        8 to (185000..192000),
        12 to (145800..149200),
        13 to (149200..151200),
        14 to (151600..153600),
        20 to (158200..160200),
        25 to (386000..399000),
        26 to (162000..167000),
        28 to (151600..160600),
        30 to (470000..480000),
        38 to (514000..524000),
        40 to (460000..480000),
        41 to (499200..537999),
        48 to (636666..646666),
        66 to (422000..440000),
        71 to (123400..130400),
        77 to (620000..680000),
        78 to (620000..653333),
        79 to (693334..733333),
    )
    val bands: Set<Int> = ranges.keys

    fun valid(band: Int, arfcn: Int): Boolean {
        val range = ranges[band] ?: return false
        if (arfcn !in range) return false
        if (band == 41) return (arfcn - 499200) % 3 == 0
        return true
    }

    fun defaultArfcn(band: Int): Int = when (band) {
        1 -> 430000
        2 -> 390000
        3 -> 370000
        5 -> 176000
        7 -> 530000
        8 -> 188000
        12 -> 147000
        13 -> 150000
        14 -> 152500
        20 -> 159000
        25 -> 392000
        26 -> 165000
        28 -> 156000
        30 -> 475000
        38 -> 519000
        40 -> 470000
        41 -> 520002
        48 -> 645000
        66 -> 430000
        71 -> 127000
        77 -> 650000
        78 -> 640000
        79 -> 713334
        else -> error("Unsupported NR band")
    }
}
