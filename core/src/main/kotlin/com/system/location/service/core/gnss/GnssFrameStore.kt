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
    private data class Key(val epochMillis: Long, val identity: ObserverIdentity, val minimum: Int,
        val receiverSecond: Long?, val bootCount: Int?)
    private var capture: GnssCapture? = null
    private var model: ReceiverBroadcastSkyModel? = null
    private val cache = object : LinkedHashMap<Key, PublishedGnssFrame?>(16, 0.75f, true) {
        // Covers adjacent seconds for all configured routes (RoutingConfig permits 1024).
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, PublishedGnssFrame?>?): Boolean = size > 4096
    }

    @Synchronized
    fun configure(observation: GnssCapture?, seed: Long = 0) {
        capture = observation
        model = observation?.let { ReceiverBroadcastSkyModel(it, seed) }
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
        val source = capture ?: return null
        if (!GnssCaptureValidator.validate(source, validationAtMillis).eligible) { cache.clear(); return null }
        val bearing = ((bearingDeg % 360) + 360) % 360
        // Null is for explicit sample previews; production must supply its stable route.
        val identity = route?.let(ObserverIdentity::Routed)
            ?: ObserverIdentity.Exact(observer, altitudeMeters, speedMps, bearing)
        // A frozen/adjusted wall clock cannot keep a physical frame alive after elapsed evidence
        // expires. Missing or changed boot identity must not reuse a physically propagated frame.
        val hasBroadcast = model?.broadcastBootCount != null
        val key = Key(atMillis / 1000 * 1000, identity, minimumSatellites,
            if (hasBroadcast) elapsedRealtimeNanos?.div(1_000_000_000L) else null,
            if (hasBroadcast) currentBootCount else null)
        if (cache.containsKey(key)) return cache[key]
        val frame = model?.frame(key.epochMillis, observer, altitudeMeters, minimumSatellites,
            elapsedRealtimeNanos, currentBootCount)
        val published = frame?.let { PublishedGnssFrame(sequence.incrementAndGet(), it, speedMps, bearing) }
        cache[key] = published
        return published
    }
}
