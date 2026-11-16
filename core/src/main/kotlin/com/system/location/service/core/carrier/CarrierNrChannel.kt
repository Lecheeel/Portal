package com.system.location.service.core.carrier

/** FR1 downlink NR-ARFCN channel rasters, TS 38.101-1 table 5.4.2.3-1.
 * Uses the supported 100/15 kHz raster, not bandwidth-dependent valid carrier-center limits.
 * https://www.etsi.org/deliver/etsi_ts/138100_138199/13810101/18.08.00_60/ts_13810101v180800p.pdf
 */
object CarrierNrChannel {
    private data class Raster(val first: Int, val last: Int, val step: Int)
    private val rasters = mapOf(
        1 to Raster(422000, 434000, 20), 2 to Raster(386000, 398000, 20),
        3 to Raster(361000, 376000, 20), 5 to Raster(173800, 178800, 20),
        7 to Raster(524000, 538000, 20), 8 to Raster(185000, 192000, 20),
        12 to Raster(145800, 149200, 20), 13 to Raster(149200, 151200, 20),
        14 to Raster(151600, 153600, 20), 20 to Raster(158200, 164200, 20),
        25 to Raster(386000, 399000, 20), 26 to Raster(171800, 178800, 20),
        28 to Raster(151600, 160600, 20), 30 to Raster(470000, 472000, 20),
        38 to Raster(514000, 524000, 20), 40 to Raster(460000, 480000, 20),
        41 to Raster(499200, 537999, 3), 48 to Raster(636667, 646666, 1),
        66 to Raster(422000, 440000, 20), 71 to Raster(123400, 130400, 20),
        77 to Raster(620000, 680000, 1), 78 to Raster(620000, 653333, 1),
        79 to Raster(693334, 733333, 1),
    )
    val bands: Set<Int> = rasters.keys

    fun valid(band: Int, arfcn: Int): Boolean = rasters[band]?.let {
        arfcn in it.first..it.last && (arfcn - it.first) % it.step == 0
    } ?: false

    fun defaultArfcn(band: Int): Int = when (band) {
        1 -> 430000; 2 -> 390000; 3 -> 370000; 5 -> 176000
        7 -> 530000; 8 -> 188000; 12 -> 147000; 13 -> 150000
        14 -> 152500; 20 -> 159000; 25 -> 392000; 26 -> 175000
        28 -> 156000; 30 -> 471000; 38 -> 519000; 40 -> 470000
        41 -> 520002; 48 -> 645000; 66 -> 430000; 71 -> 127000
        77 -> 650000; 78 -> 640000; 79 -> 713334
        else -> error("Unsupported NR band")
    }
}
