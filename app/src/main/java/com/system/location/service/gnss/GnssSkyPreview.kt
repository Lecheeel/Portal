package com.system.location.service.gnss

import com.system.location.service.core.gnss.*
import com.system.location.service.core.location.LocationSample

/**
 * Local prediction using the same receiver model as injection, with private model ownership.
 * Never calls the process-wide publication cache: opening UI must not anchor a production route
 * to a different first sample or advance its publication sequence.
 */
internal class GnssSkyPreview {
    private data class Key(val policy: GnssSimulationPolicy, val digest: String?, val source: String?)
    private var key: Key? = null
    private var model: ReceiverAutonomousSkyModel? = null

    fun frame(sample: LocationSample, minimum: Int, policy: GnssSimulationPolicy,
        navigation: RinexNavigationResource?): ReceiverGnssFrame? {
        if (policy.mode == GnssSimulationMode.OFF || policy.mode == GnssSimulationMode.REPLAY) return null
        navigation?.validate()
        val next = Key(policy, navigation?.sha256, navigation?.sourceUrl)
        if (key != next) {
            model = GnssBundledOrbitResources.model(policy, GnssOutputLimits(128, 36, 32),
                gpsAlmanacAgeBudgetSeconds = 7 * 86400.0, navigation = navigation)
            key = next
        }
        return model!!.frame(GnssSkyRequest(sample.timeMillis, sample.coordinate, sample.altitude), minimum)
    }
}
