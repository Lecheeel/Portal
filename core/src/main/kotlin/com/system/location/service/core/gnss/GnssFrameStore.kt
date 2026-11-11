package com.system.location.service.core.gnss

import com.system.location.service.core.geo.Wgs84
import java.util.concurrent.atomic.AtomicLong

data class PublishedGnssFrame(
    val sequence: Long,
    val frame: ReceiverGnssFrame,
    val speedMps: Double,
    val bearingDeg: Double,
)

/** First sample of each route/clock-second anchors one immutable publication for all adapters. */
class GnssFrameStore(private val sequence: AtomicLong = AtomicLong()) {
    private sealed interface ObserverIdentity {
        data class Routed(val route: GnssFrameRoute) : ObserverIdentity
        data class Exact(val observer: Wgs84, val altitude: Double, val speed: Double,
            val bearing: Double) : ObserverIdentity
    }
    private data class Key(val utcEpochMillis: Long?, val identity: ObserverIdentity, val minimum: Int,
        val receiverSecond: Long?, val bootCount: Int?)
    private var capture: GnssCapture? = null
    private var model: ReceiverBroadcastSkyModel? = null
    private var autonomous: ReceiverAutonomousSkyModel? = null
    private val cache = object : LinkedHashMap<Key, PublishedGnssFrame?>(16, 0.75f, true) {
        // Covers adjacent seconds for all configured routes (RoutingConfig permits 1024).
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, PublishedGnssFrame?>?): Boolean = size > 4096
    }

    @Synchronized
    fun configure(observation: GnssCapture?, seed: Long = 0) {
        capture = observation
        autonomous = null
        model = observation?.let { ReceiverBroadcastSkyModel(it, seed) }
        cache.clear()
    }

    /** Autonomous geometry has its own resource lifecycle and never requires a capture. */
    @Synchronized
    fun configureAutonomous(sky: ReceiverAutonomousSkyModel) {
        capture = null
        model = null
        autonomous = sky
        cache.clear()
    }

    @Synchronized
    fun current(atMillis: Long, observer: Wgs84, altitudeMeters: Double,
        minimumSatellites: Int, speedMps: Double, bearingDeg: Double,
        validationAtMillis: Long = atMillis, route: GnssFrameRoute? = null,
        elapsedRealtimeNanos: Long? = null, currentBootCount: Int? = null): PublishedGnssFrame? {
        require(atMillis >= 0 && validationAtMillis >= 0 && speedMps.isFinite() && speedMps >= 0 && bearingDeg.isFinite())
        require(altitudeMeters.isFinite() && minimumSatellites in 0..GnssFrame.MAX_SATELLITES)
        require(elapsedRealtimeNanos == null || elapsedRealtimeNanos >= 0)
        require(currentBootCount == null || currentBootCount >= 0)
        val sky = autonomous
        if (sky == null) {
            val source = capture ?: return null
            if (!GnssCaptureValidator.validate(source, validationAtMillis).eligible) { cache.clear(); return null }
            // A delayed provider sample cannot use evidence that had not finished at its epoch.
            // Do not cache this rejection: a later sample in the same second can be eligible.
            if (atMillis < source.endedAtMillis) return null
        }
        val bearing = ((bearingDeg % 360) + 360) % 360
        // Null is for explicit sample previews; production must supply its stable route.
        val identity = route?.let(ObserverIdentity::Routed)
            ?: ObserverIdentity.Exact(observer, altitudeMeters, speedMps, bearing)
        // A frozen/adjusted wall clock cannot keep a physical frame alive after elapsed evidence
        // expires. Missing or changed boot identity must not reuse a physically propagated frame.
        val hasBroadcast = model?.broadcastBootCount != null
        val utcEpochMillis = atMillis / 1000 * 1000
        val receiverSecond = if (hasBroadcast || sky != null) elapsedRealtimeNanos?.div(1_000_000_000L) else null
        // Choose one clock boundary: intersecting UTC and elapsed seconds would publish twice
        // per receiver second when their boundaries differ. UTC remains the first sample's label.
        val key = Key(if (receiverSecond == null) utcEpochMillis else null, identity, minimumSatellites,
            receiverSecond,
            if (hasBroadcast || sky != null) currentBootCount else null)
        if (cache.containsKey(key)) return cache[key]
        // The second identifies a cache bucket, not the observation epoch. Keep the first
        // sample's exact time for every source; rounding could precede capture completion.
        val frame = if (sky != null) sky.frame(GnssSkyRequest(atMillis, observer, altitudeMeters), minimumSatellites, validationAtMillis)
            else model?.frame(atMillis, observer, altitudeMeters, minimumSatellites,
                elapsedRealtimeNanos, currentBootCount)
        val published = frame?.let { PublishedGnssFrame(sequence.incrementAndGet(), it, speedMps, bearing) }
        cache[key] = published
        return published
    }
}
