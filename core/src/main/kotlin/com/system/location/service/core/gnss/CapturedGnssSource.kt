package com.system.location.service.core.gnss

import com.system.location.service.core.location.LocationSample
import java.util.concurrent.atomic.AtomicLong

/**
 * One immutable receiver-evidence identity with its own synchronized publication cache.
 * Prepare a replacement before publishing configuration; old readers keep their original
 * source, including its frame cache. No method can reconfigure an already published source.
 * This process-local handle is deliberately not serializable. Only GnssCaptureWire crosses IPC.
 */
class CapturedGnssSource private constructor(private val payload: String) {
    private val capture = GnssCaptureWire.decode(payload)
    private val frames = GnssFrameStore(sequences).apply { configure(capture) }
    val requiresBootIdentity: Boolean = capture.bootCount != null &&
        (capture.gpsBroadcastEvidence != null || capture.measurements.isNotEmpty() || capture.navigation.isNotEmpty())

    fun frame(sample: LocationSample, minimumSatellites: Int, route: GnssFrameRoute,
        validationAtMillis: Long, currentBootCount: Int? = null): PublishedGnssFrame? =
        frames.current(sample.timeMillis, sample.coordinate, sample.altitude, minimumSatellites,
            sample.speed.toDouble(), sample.bearing.toDouble(), validationAtMillis, route,
            sample.elapsedNanos, currentBootCount)

    /** Bounded status/compact broadcast evidence for an authenticated peer's synchronization. */
    fun wirePayload(): String = payload

    companion object {
        private val sequences = AtomicLong()

        fun prepare(payload: String?, previous: CapturedGnssSource? = null): CapturedGnssSource? = when {
            payload == null -> null
            payload == previous?.payload -> previous
            else -> CapturedGnssSource(payload)
        }
    }
}
