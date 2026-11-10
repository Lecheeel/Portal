package com.system.location.service.core.gnss

import com.system.location.service.core.geo.Wgs84

data class PublishedGnssFrame(
    val sequence: Long,
    val frame: ReceiverGnssFrame,
    val speedMps: Double,
    val bearingDeg: Double,
)

/** One immutable frame per second and observer; every output adapter reads this same publication. */
class GnssFrameStore {
    private data class Key(val epochMillis: Long, val observer: Wgs84, val altitude: Double,
        val minimum: Int, val speed: Double, val bearing: Double)
    private var capture: GnssCapture? = null
    private var model: ReceiverGnssSkyModel? = null
    private var sequence = 0L
    private val cache = object : LinkedHashMap<Key, PublishedGnssFrame?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, PublishedGnssFrame?>?): Boolean = size > 128
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
        validationAtMillis: Long = atMillis): PublishedGnssFrame? {
        require(atMillis >= 0 && validationAtMillis >= 0 && speedMps.isFinite() && speedMps >= 0 && bearingDeg.isFinite())
        val source = capture ?: return null
        if (!GnssCaptureValidator.validate(source, validationAtMillis).eligible) { cache.clear(); return null }
        val key = Key(atMillis / 1000 * 1000, observer, altitudeMeters, minimumSatellites, speedMps,
            ((bearingDeg % 360) + 360) % 360)
        if (cache.containsKey(key)) return cache[key]
        val frame = model?.frame(key.epochMillis, observer, altitudeMeters, minimumSatellites)
        val published = frame?.let { PublishedGnssFrame(++sequence, it, key.speed, key.bearing) }
        cache[key] = published
        return published
    }
}
