package com.system.location.service.hook.hooks.gnss

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.GnssCaptureWire
import com.system.location.service.core.gnss.GnssFrameStore
import com.system.location.service.core.gnss.PublishedGnssFrame
import com.system.location.service.hook.utils.FakeLoc

/** Configured only by the authenticated command channel. No catalog fallback exists here. */
object GnssReceiverRuntime {
    private val frames = GnssFrameStore()
    private var configuredPayload: String? = null

    @Synchronized
    fun configure(payload: String?) {
        if (payload == configuredPayload) return
        val capture = payload?.let(GnssCaptureWire::decode)
        frames.configure(capture)
        configuredPayload = payload
    }

    fun currentFrame(point: Wgs84? = null): PublishedGnssFrame? {
        return frames.current(System.currentTimeMillis(), point ?: Wgs84(FakeLoc.latitude, FakeLoc.longitude),
            FakeLoc.altitude, FakeLoc.minSatellites, if (point == null) FakeLoc.speed.coerceAtLeast(0.0) else 0.0,
            if (point == null) FakeLoc.bearing else 0.0)
    }
}
