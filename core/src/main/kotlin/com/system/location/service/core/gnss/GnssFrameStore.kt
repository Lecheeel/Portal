package com.system.location.service.core.gnss

import com.system.location.service.core.geo.Wgs84

data class PublishedGnssFrame(
    val sequence: Long,
    val frame: ReceiverGnssFrame,
    val speedMps: Double,
    val bearingDeg: Double,
)

/** First sample of each route/second anchors one immutable receiver publication for all adapters. */
class GnssFrameStore {
    private sealed interface ObserverIdentity {
        data class Routed(val route: GnssFrameRoute) : ObserverIdentity
        data class Exact(val observer: Wgs84, val altitude: Double, val speed: Double,
            val bearing: Double) : ObserverIdentity
    }
    private data class Key(val epochMillis: Long, val identity: ObserverIdentity, val minimum: Int)
    private var capture: GnssCapture? = null
    private var model: ReceiverGnssSkyModel? = null
    private var sequence = 0L
    private val cache = object : LinkedHashMap<Key, PublishedGnssFrame?>(16, 0.75f, true) {
        // Covers adjacent seconds for all configured routes (RoutingConfig permits 1024).
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, PublishedGnssFrame?>?): Boolean = size > 4096
    }

    @Synchronized
    fun configure(observation: GnssCapture?, seed: Long = 0) {
        capture = observation
        model = observation?.let { ReceiverGnssSkyModel(it, seed) }
        cache.clear()
    }

    @Synchronized
    fun current(atMillis: Long, observer: Wgs84, altitudeMeters: Double,
        minimumSatellites: Int, speedMps: Double, bearingDeg: Double,
        validationAtMillis: Long = atMillis, route: GnssFrameRoute? = null): PublishedGnssFrame? {
        require(atMillis >= 0 && validationAtMillis >= 0 && speedMps.isFinite() && speedMps >= 0 && bearingDeg.isFinite())
        require(altitudeMeters.isFinite() && minimumSatellites in 0..GnssFrame.MAX_SATELLITES)
        val source = capture ?: return null
        if (!GnssCaptureValidator.validate(source, validationAtMillis).eligible) { cache.clear(); return null }
        val bearing = ((bearingDeg % 360) + 360) % 360
        // Null is for explicit sample previews; production must supply its stable route.
        val identity = route?.let(ObserverIdentity::Routed)
            ?: ObserverIdentity.Exact(observer, altitudeMeters, speedMps, bearing)
        val key = Key(atMillis / 1000 * 1000, identity, minimumSatellites)
        if (cache.containsKey(key)) return cache[key]
        val frame = model?.frame(key.epochMillis, observer, altitudeMeters, minimumSatellites)
        val published = frame?.let { PublishedGnssFrame(++sequence, it, speedMps, bearing) }
        cache[key] = published
        return published
    }
}
