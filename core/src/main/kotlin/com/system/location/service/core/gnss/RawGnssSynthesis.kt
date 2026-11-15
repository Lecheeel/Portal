package com.system.location.service.core.gnss

import com.system.location.service.core.runtime.HookConfiguration
import java.util.concurrent.ConcurrentHashMap

/**
 * Production inputs for GPS L1 C/A raw observations.
 *
 * Preferred input is the uploaded RINEX broadcast navigation resource. When it has no applicable
 * GPS ephemerides, the bundled Yuma almanac is promoted into an LNAV-shaped orbit with zero
 * harmonic/clock corrections. The result is explicitly an approximate, self-consistent model, not
 * a claim that the receiver observed the real broadcast upload.
 */
object RawGnssSynthesis {
    const val MIN_MEASUREMENTS = GpsL1RawMeasurementSynthesizer.MIN_MEASUREMENTS
    // GPS almanac orbits are approximate by definition; keep the synthetic model usable for a
    // month instead of making raw availability expire a week after the bundled epoch.
    private const val ALMANAC_VALIDITY_SECONDS = 30 * 86400.0
    private val rinexCache = ConcurrentHashMap<String, List<GpsLnavEphemeris>>()
    private val almanacCache = ConcurrentHashMap<String, List<GpsLnavEphemeris>>()

    fun gpsTime(nowMillis: Long): GpsTime? {
        if (nowMillis < 0) return null
        return GnssBundledOrbitResources.utcTimeScale()
            ?.gpsAtUtcMillis(nowMillis)?.time
    }

    /**
     * RINEX ephemerides first, then bundled almanac identities not already supplied. The caller
     * still filters this list against the published sky and requires four usable measurements.
     */
    fun ephemerides(
        navigation: RinexNavigationResource?,
        nowMillis: Long,
    ): List<GpsLnavEphemeris> = runCatching {
        val time = gpsTime(nowMillis) ?: return emptyList()
        val rinex = navigation?.let { resource ->
            runCatching {
                rinexCache.getOrPut(resource.sha256) {
                    val file = RinexNavigationReader.read(resource.navigation)
                    file.records
                        .filter { it.system == RinexNavigationSystem.GPS }
                        .mapNotNull { record ->
                            runCatching { RinexGpsImporter.decode(record).ephemeris }.getOrNull()
                        }
                        .filter { it.health == 0 }
                        .distinctBy { it.svid }
                }
            }.getOrDefault(emptyList())
        }.orEmpty().filter { ephemerisUsable(it, time) }
        val almanac = almanacEphemerides().filterNot { candidate ->
            rinex.any { it.svid == candidate.svid }
        }.filter { ephemerisUsable(it, time) }
        (rinex + almanac).distinctBy { it.svid }
    }.getOrDefault(emptyList())

    fun canSynthesize(configuration: HookConfiguration, nowMillis: Long): Boolean = runCatching {
        configuration.enabled && configuration.gnssEnabled &&
            configuration.gnssFrameSource() != null &&
            ephemerides(configuration.gnssNavigation, nowMillis).size >= MIN_MEASUREMENTS
    }.getOrDefault(false)

    private fun ephemerisUsable(ephemeris: GpsLnavEphemeris, time: GpsTime): Boolean =
        ephemeris.validFrom.secondsSince(time) <= 0.0 && time.secondsSince(ephemeris.validUntil) <= 0.0

    private fun almanacEphemerides(): List<GpsLnavEphemeris> =
        GnssBundledOrbitResources.gpsAlmanac()?.let { records ->
            val key = records.joinToString("|") { "${it.svid}:${it.referenceTime.week}:${it.referenceTime.secondsOfWeek}" }
            almanacCache.getOrPut(key) {
                records.filter { it.health == 0 }.mapNotNull(::fromAlmanac).distinctBy { it.svid }
            }
        }.orEmpty()

    internal fun fromAlmanac(almanac: GpsYumaAlmanac): GpsLnavEphemeris? = runCatching {
        val toe = almanac.referenceTime
        GpsLnavEphemeris(
            svid = almanac.svid,
            toe = toe,
            toc = toe,
            validFrom = toe.plusSeconds(-ALMANAC_VALIDITY_SECONDS),
            validUntil = toe.plusSeconds(ALMANAC_VALIDITY_SECONDS),
            iode2 = 0,
            iode3 = 0,
            iodc = 0,
            health = almanac.health,
            sqrtSemiMajorAxis = almanac.sqrtSemiMajorAxis,
            eccentricity = almanac.eccentricity,
            inclinationRad = almanac.inclinationRad,
            ascendingNodeRad = almanac.ascendingNodeRad,
            argumentOfPerigeeRad = almanac.argumentOfPerigeeRad,
            meanAnomalyRad = almanac.meanAnomalyRad,
            // Yuma has no harmonic corrections or delta-n; the vacuum Kepler model is exact for
            // these zero corrections and the same almanac elements used by the published sky.
            deltaMeanMotionRadPerSecond = 0.0,
            ascendingNodeRateRadPerSecond = almanac.ascendingNodeRateRadPerSecond,
            inclinationRateRadPerSecond = 0.0,
            cucRad = 0.0,
            cusRad = 0.0,
            crcMeters = 0.0,
            crsMeters = 0.0,
            cicRad = 0.0,
            cisRad = 0.0,
            af0Seconds = almanac.af0Seconds,
            af1SecondsPerSecond = almanac.af1SecondsPerSecond,
            af2SecondsPerSecondSquared = 0.0,
            tgdSeconds = 0.0,
        )
    }.getOrNull()
}
