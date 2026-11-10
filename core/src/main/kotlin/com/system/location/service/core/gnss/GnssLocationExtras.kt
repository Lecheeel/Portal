package com.system.location.service.core.gnss

/** Android GPS extras describe satellites used in the fix, matching GGA rather than GSV. */
data class GnssLocationExtras(val satellites: Int, val meanCn0DbHz: Float?, val maxCn0DbHz: Float?) {
    companion object {
        fun from(publication: PublishedGnssFrame): GnssLocationExtras {
            val used = if (publication.frame.geometry.dop == null) emptyList()
                else publication.frame.satellites.filter { it.usedInFix }
            return GnssLocationExtras(used.size,
                used.takeIf { it.isNotEmpty() }?.map { it.cn0DbHz }?.average()?.toFloat(),
                used.maxOfOrNull { it.cn0DbHz })
        }
    }
}
