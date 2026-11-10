package com.system.location.service.hook.hooks.gnss

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.GnssCaptureWire
import com.system.location.service.core.gnss.GnssFrameStore
import com.system.location.service.core.gnss.PublishedGnssFrame
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.ProviderView
import com.system.location.service.core.location.LocationSample

/** Configured only by the authenticated command channel. No catalog fallback exists here. */
object GnssReceiverRuntime {
    private val frames = GnssFrameStore()
    @Volatile private var configuredPayload: String? = null

    @Synchronized
    fun configure(payload: String?) {
        if (payload == configuredPayload) return
        val capture = payload?.let(GnssCaptureWire::decode)
        frames.configure(capture)
        configuredPayload = payload
    }

    fun currentFrame(point: Wgs84? = null): PublishedGnssFrame? {
        if (configuredPayload == null) return null
        return frameForSample(ProviderView.sampleFor("gps", point = point))
    }

    fun frameForSample(sample: LocationSample): PublishedGnssFrame? =
        frames.current(sample.timeMillis, sample.coordinate, sample.altitude, FakeLoc.minSatellites,
            sample.speed.toDouble(), sample.bearing.toDouble(), validationAtMillis = System.currentTimeMillis())
}
