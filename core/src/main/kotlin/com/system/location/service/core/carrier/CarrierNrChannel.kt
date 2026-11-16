package com.system.location.service.core.carrier

/** Supported FR1 TDD channel rasters for the synthetic receiver. Frequencies are not live scans. */
object CarrierNrChannel {
    val bands = setOf(41, 77, 78, 79)

    fun valid(band: Int, arfcn: Int): Boolean = when (band) {
        41 -> arfcn in 499200..537999 && (arfcn - 499200) % 3 == 0
        77 -> arfcn in 620000..680000
        78 -> arfcn in 620000..653333
        79 -> arfcn in 693334..733333
        else -> false
    }

    fun defaultArfcn(band: Int): Int = when (band) {
        41 -> 520002
        77 -> 650000
        78 -> 640000
        79 -> 713334
        else -> error("Unsupported NR band")
    }
}
