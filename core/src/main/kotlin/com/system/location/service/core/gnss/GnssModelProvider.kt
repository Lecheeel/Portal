package com.system.location.service.core.gnss

/** P3-05: production analytic model vs experimental orbital/Kepler model. */
enum class GnssModelMode { ANALYTIC, ORBITAL }

object GnssSkyModelProvider {
    fun create(mode: GnssModelMode): GnssSkyModel = when (mode) {
        GnssModelMode.ANALYTIC -> CatalogGnssSkyModel()
        GnssModelMode.ORBITAL -> OrbitalGnssSkyModel()
    }
}
