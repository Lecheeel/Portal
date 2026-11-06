package com.system.location.service.core

import com.system.location.service.core.gnss.CatalogGnssSkyModel
import com.system.location.service.core.gnss.GnssModelMode
import com.system.location.service.core.gnss.GnssSkyModelProvider
import com.system.location.service.core.gnss.OrbitalGnssSkyModel
import org.junit.Assert.assertTrue
import org.junit.Test

class GnssModelProviderTest {
    @Test fun analyticModeReturnsProductionModel() {
        assertTrue(GnssSkyModelProvider.create(GnssModelMode.ANALYTIC) is CatalogGnssSkyModel)
    }

    @Test fun orbitalModeReturnsExperimentalModel() {
        assertTrue(GnssSkyModelProvider.create(GnssModelMode.ORBITAL) is OrbitalGnssSkyModel)
    }
}
