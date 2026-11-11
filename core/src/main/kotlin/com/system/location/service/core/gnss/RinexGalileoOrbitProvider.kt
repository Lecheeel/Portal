package com.system.location.service.core.gnss

/**
 * Application limits for approximate sky geometry, not a broadcast GGTO validity guarantee.
 * RINEX A5 supplies no expiry. A missing/conflicting/too-distant GAGP must cause fallback.
 * Frame approximation is opt-in: Galileo OS SDD §1.6.5 describes GPS/GTRF interoperability
 * without further receiver action for most OS applications. This does NOT establish an exact
 * realization/epoch transformation, centimetre accuracy, pseudoranges or satellite clock accuracy.
 * https://www.gsc-europa.eu/sites/default/files/sites/all/files/Galileo-OS-SDD_in_force.pdf
 */
data class RinexGalileoSkyPolicy(
    val maximumTimeCorrectionDistanceSeconds: Double,
    val allowAlignedFrameApproximation: Boolean,
) {
    init { require(maximumTimeCorrectionDistanceSeconds.isFinite() && maximumTimeCorrectionDistanceSeconds > 0) }
}

/** Galileo E1 sky adapter; F/NAV alone cannot establish E1-B service health. */
class RinexGalileoOrbitProvider(
    file: RinexNavigationFile,
    private val provenance: GnssOrbitProvenance,
    private val utc: GnssUtcTimeScale,
    private val policy: RinexGalileoSkyPolicy,
) : GnssOrbitProvider {
    override val kind = GnssOrbitSourceKind.BROADCAST_EPHEMERIS
    private val reserved = file.records.filter { it.system == RinexNavigationSystem.GALILEO && it.svid in 1..36 }
        .map { Constellation.GALILEO to it.svid }.toSet()
    private val diagnostics = mutableListOf<String>()
    private val correction = runCatching { RinexTimeSystemCorrection.galileoCorrection(file.timeSystemCorrections) }
    private val records = file.records.filter { it.system == RinexNavigationSystem.GALILEO }.mapNotNull { record ->
        runCatching { RinexGalileoImporter.decode(record) }.getOrElse {
            diagnostics += "Galileo:${record.svid}: ${it.message}"
            null
        }
    }.filter { it.ephemeris.clockReference == GalileoClockReference.INAV_E1_E5B }
        .groupBy { it.ephemeris.svid }

    init { require(provenance.kind == kind) }

    override fun positions(request: GnssSkyRequest): GnssOrbitBatch {
        fun unavailable(reason: String) = GnssOrbitBatch(emptyList(), diagnostics + reason, reserved)
        if (!policy.allowAlignedFrameApproximation)
            return unavailable("Galileo: approximate GTRF/WGS84 sky alignment was not permitted")
        val c = correction.getOrNull()
            ?: return unavailable("Galileo: missing, damaged or conflicting RINEX GAGP correction")
        if (c.referenceWeek < 1024) return unavailable("Galileo: GAGP reference precedes GST origin")
        val reference = GalileoTime(c.referenceWeek - 1024, c.referenceSeconds.toDouble())
        val interval = runCatching { reference.plusSeconds(-policy.maximumTimeCorrectionDistanceSeconds) to
            reference.plusSeconds(policy.maximumTimeCorrectionDistanceSeconds) }.getOrNull()
            ?: return unavailable("Galileo: unsupported correction policy interval")
        val gps = utc.gpsAtUtcMillis(request.atMillis)
        val gpsTime = gps.time ?: return unavailable("Galileo: ${gps.reason}")
        val time = c.gpsToGalileo(gpsTime, interval.first, interval.second)
            ?: return unavailable("Galileo: GAGP outside application age limit or not invertible")
        val messages = diagnostics.toMutableList()
        messages += "Galileo: approximate GTRF/WGS84 sky alignment; GAGP age limited by application policy, not guaranteed broadcast validity"
        val blocked = mutableSetOf<Pair<Constellation, Int>>()
        val positions = records.mapNotNull { (svid, candidates) ->
            val transmitted = candidates.filter { time.secondsSince(it.availableFrom) >= 0 }
            val latestTime = transmitted.maxByOrNull { it.availableFrom.week * 604800.0 + it.availableFrom.secondsOfWeek }?.availableFrom
            val latest = transmitted.filter { it.availableFrom == latestTime }.distinct()
            fun applicable(record: RinexGalileoBroadcast) = time.secondsSince(record.ephemeris.validFrom) >= 0 &&
                record.ephemeris.validUntil.secondsSince(time) >= 0
            if (latest.any { applicable(it) && it.service.e1bHealthy == false })
                blocked += Constellation.GALILEO to svid
            val accepted = latest.singleOrNull()
            if (accepted == null || !applicable(accepted) || accepted.service.e1bHealthy != true ||
                !accepted.service.accuracyAvailable) {
                messages += "Galileo:$svid: no unique applicable healthy E1 RINEX upload with SISA"
                return@mapNotNull null
            }
            val state = GalileoBroadcastPropagator.propagate(accepted.ephemeris, time).state
            val referenceGps = c.galileoToGps(accepted.ephemeris.toe, interval.first, interval.second)
            val epoch = referenceGps?.let { utc.utcAtGpsTime(it).utcMillis }
            if (state == null || epoch == null) {
                messages += "Galileo:$svid: propagation or reference time conversion unavailable"
                null
            } else GnssOrbitPosition(Constellation.GALILEO, svid,
                EcefPosition(state.xMeters, state.yMeters, state.zMeters),
                provenance.copy(referenceEpochMillis = epoch), hasEphemeris = true, hasAlmanac = false)
        }
        return GnssOrbitBatch(positions, messages, reserved, blocked)
    }
}
