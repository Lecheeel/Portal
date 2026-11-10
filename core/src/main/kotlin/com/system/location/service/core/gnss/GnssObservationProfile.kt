package com.system.location.service.core.gnss

import com.system.location.service.core.geo.Wgs84
import kotlinx.serialization.Serializable
import kotlin.random.Random

/** One receiver status frame captured before synthetic GNSS is enabled. */
@Serializable
data class GnssObservationFrame(
    val capturedAtMillis: Long,
    val observer: Wgs84? = null,
    val altitudeMeters: Double? = null,
    val satellites: List<GnssSatellite>,
    val measurementsAvailable: Boolean = false,
    val navigationMessagesAvailable: Boolean = false,
    /** Original per-signal status records. Empty means legacy evidence was not captured. */
    val signalObservations: List<GnssSignalObservation> = emptyList(),
) {
    init {
        require(signalObservations.size <= 512) { "too many signal observations" }
        require(capturedAtMillis >= 0L) { "capturedAtMillis must be non-negative" }
        require(altitudeMeters == null || altitudeMeters.isFinite()) { "invalid altitude" }
        require(satellites.map { it.constellation to it.svid }.distinct().size == satellites.size) {
            "duplicate constellation/svid"
        }
    }
}

@Serializable
data class GnssConstellationProfile(
    val constellation: Constellation,
    val frameCount: Int,
    val satelliteSamples: Int,
    val usedSamples: Int,
    val frameCountHistogram: Map<Int, Int>,
    val elevationHistogram: Map<Int, Int>,
    val cn0Histogram: Map<Int, Int>,
    val identities: List<GnssSatelliteIdentity>,
    val ratio: Double,
    val ratioConfidenceLow: Double,
    val ratioConfidenceHigh: Double,
) {
    init {
        require(frameCount > 0) { "frameCount must be positive" }
        require(satelliteSamples >= 0 && usedSamples in 0..satelliteSamples) {
            "invalid satellite sample counts"
        }
        require(ratio in 0.0..1.0) { "ratio out of range" }
        require(ratioConfidenceLow in 0.0..1.0 && ratioConfidenceHigh in 0.0..1.0) {
            "confidence interval out of range"
        }
        require(ratioConfidenceLow <= ratio && ratio <= ratioConfidenceHigh) {
            "ratio outside confidence interval"
        }
        require(identities.map { it.constellation to it.svid }.distinct().size == identities.size) {
            "duplicate satellite identity"
        }
    }

    val meanSatellitesPerFrame: Double
        get() = satelliteSamples.toDouble() / frameCount

    val usedRatio: Double
        get() = if (satelliteSamples == 0) 0.0 else usedSamples.toDouble() / satelliteSamples
}

@Serializable
data class GnssSatelliteIdentity(
    val constellation: Constellation,
    val svid: Int,
    val hasEphemeris: Boolean,
    val hasAlmanac: Boolean,
    val carrierHz: Float? = null,
) {
    init {
        require(svid in 1..999) { "svid out of range: $svid" }
        require(carrierHz == null || carrierHz.isFinite() && carrierHz > 0f) {
            "invalid carrier frequency"
        }
    }
}

@Serializable
data class GnssObservationProfile(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val deviceModel: String,
    val osVersion: String,
    val capturedAtMillis: Long,
    val captureEndedAtMillis: Long,
    val observer: Wgs84? = null,
    val altitudeMeters: Double? = null,
    val frameCount: Int,
    val constellations: List<GnssConstellationProfile>,
    val measurementsAvailable: Boolean,
    val navigationMessagesAvailable: Boolean,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "unsupported schemaVersion: $schemaVersion" }
        require(deviceModel.isNotBlank()) { "deviceModel must not be blank" }
        require(osVersion.isNotBlank()) { "osVersion must not be blank" }
        require(capturedAtMillis >= 0L && captureEndedAtMillis >= capturedAtMillis) {
            "invalid capture interval"
        }
        require(frameCount > 0) { "frameCount must be positive" }
        require(altitudeMeters == null || altitudeMeters.isFinite()) { "invalid altitude" }
        require(constellations.map { it.constellation }.distinct().size == constellations.size) {
            "duplicate constellation profile"
        }
        require(constellations.all { it.frameCount == frameCount }) {
            "constellation frame count must match profile frame count"
        }
    }

    val totalSatelliteSamples: Int
        get() = constellations.sumOf { it.satelliteSamples }

    fun profileFor(constellation: Constellation): GnssConstellationProfile? =
        constellations.firstOrNull { it.constellation == constellation }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

/** Aggregates frames without giving a busy frame more weight than its satellite samples. */
object GnssObservationProfileBuilder {
    fun build(
        frames: List<GnssObservationFrame>,
        deviceModel: String,
        osVersion: String,
    ): GnssObservationProfile {
        require(frames.isNotEmpty()) { "at least one observation frame is required" }
        val ordered = frames.sortedBy { it.capturedAtMillis }
        val first = ordered.first().capturedAtMillis
        val last = ordered.last().capturedAtMillis
        val constellations = Constellation.values().mapNotNull { constellation ->
            val observations = ordered.map { frame ->
                frame.satellites.filter { it.constellation == constellation }
            }
            if (observations.all { it.isEmpty() }) return@mapNotNull null
            val satellites = observations.flatten()
            val totalSatelliteSamples = satellites.size
            val totalSamples = ordered.sumOf { it.satellites.size }
            val ratio = if (totalSamples == 0) 0.0 else totalSatelliteSamples.toDouble() / totalSamples
            val interval = blockBootstrapInterval(ordered, constellation, ratio)
            GnssConstellationProfile(
                constellation = constellation,
                frameCount = ordered.size,
                satelliteSamples = totalSatelliteSamples,
                usedSamples = satellites.count { it.usedInFix },
                frameCountHistogram = observations.groupingBy { it.size }.eachCount(),
                elevationHistogram = satellites.groupingBy { it.elevationDeg.toInt().coerceIn(-90, 90) }.eachCount(),
                cn0Histogram = satellites.groupingBy { it.cn0DbHz.toInt().coerceIn(0, GnssFrame.MAX_CN0_DB_HZ.toInt()) }.eachCount(),
                identities = satellites
                    .distinctBy { it.constellation to it.svid }
                    .map { satellite ->
                        GnssSatelliteIdentity(
                            constellation = satellite.constellation,
                            svid = satellite.svid,
                            hasEphemeris = satellite.hasEphemeris,
                            hasAlmanac = satellite.hasAlmanac,
                            carrierHz = satellite.carrierHz,
                        )
                    }
                    .sortedWith(compareBy({ it.constellation.ordinal }, { it.svid })),
                ratio = ratio,
                ratioConfidenceLow = interval.first,
                ratioConfidenceHigh = interval.second,
            )
        }
        val observers = ordered.mapNotNull { it.observer }
        val altitudes = ordered.mapNotNull { it.altitudeMeters }
        return GnssObservationProfile(
            capturedAtMillis = first,
            captureEndedAtMillis = last,
            observer = observers.firstOrNull(),
            altitudeMeters = altitudes.firstOrNull(),
            frameCount = ordered.size,
            deviceModel = deviceModel,
            osVersion = osVersion,
            constellations = constellations,
            measurementsAvailable = ordered.any { it.measurementsAvailable },
            navigationMessagesAvailable = ordered.any { it.navigationMessagesAvailable },
        )
    }

    private fun blockBootstrapInterval(
        frames: List<GnssObservationFrame>, constellation: Constellation, ratio: Double,
    ): Pair<Double, Double> {
        // Satellite observations in adjacent frames are correlated. Resample whole ten-second
        // temporal blocks rather than treating every tracked satellite as a Bernoulli trial.
        // This describes this capture only; it is not a confidence claim about other regions.
        val start = frames.first().capturedAtMillis
        val blocks = frames.groupBy { (it.capturedAtMillis - start) / 10_000L }.values.map { block ->
            block.sumOf { frame -> frame.satellites.count { it.constellation == constellation } } to
                block.sumOf { it.satellites.size }
        }
        if (blocks.size < 3) return 0.0 to 1.0
        val random = Random(0)
        val ratios = DoubleArray(1000) {
            var selected = 0L
            var total = 0L
            repeat(blocks.size) {
                val block = blocks[random.nextInt(blocks.size)]
                selected += block.first
                total += block.second
            }
            if (total == 0L) 0.0 else selected.toDouble() / total
        }.sorted()
        return minOf(ratio, ratios[24]) to maxOf(ratio, ratios[974])
    }
}
