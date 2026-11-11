package com.system.location.service.core.gnss

/**
 * Explicit application bounds for approximate sky geometry. RINEX A14 supplies no fit field;
 * this age budget is NOT an inferred fit duration or a promise of broadcast orbit accuracy.
 * BDCS and WGS84-compatible ECEF axes are approximated only with explicit permission, without
 * claiming an exact realization/epoch transformation, pseudoranges or precise clock solutions.
 */
data class RinexBeiDouSkyPolicy(val maximumEphemerisDistanceSeconds: Double,
    val maximumTimeCorrectionDistanceSeconds: Double, val allowAlignedFrameApproximation: Boolean) {
    init {
        require(maximumEphemerisDistanceSeconds.isFinite() && maximumEphemerisDistanceSeconds > 0)
        require(maximumTimeCorrectionDistanceSeconds.isFinite() && maximumTimeCorrectionDistanceSeconds > 0)
    }
}

/** Native BDT propagation with dated SVN orbit classes and B1I health, never PRN heuristics. */
class RinexBeiDouOrbitProvider(file: RinexNavigationFile, private val provenance: GnssOrbitProvenance,
    private val utc: GnssUtcTimeScale, private val catalog: BeiDouSkyCatalog,
    private val policy: RinexBeiDouSkyPolicy) : GnssOrbitProvider {
    override val kind = GnssOrbitSourceKind.BROADCAST_EPHEMERIS
    private val diagnostics = mutableListOf<String>()
    private val reserved = file.records.filter { it.system == RinexNavigationSystem.BEIDOU && it.svid in 1..63 }
        .map { Constellation.BEIDOU to it.svid }.toSet()
    private val timeScale = runCatching {
        RinexBeiDouTimeScale(utc, requireNotNull(RinexTimeSystemCorrection.beiDouCorrection(file.timeSystemCorrections)) {
            "Missing BeiDou BDUT correction"
        }, policy.maximumTimeCorrectionDistanceSeconds)
    }
    private data class Upload(val transmitted: BeiDouTime?, val decoded: RinexBeiDouBroadcast?,
        val identity: BeiDouSatelliteIdentity?, val referenceUtcMillis: Long?)
    private val records = file.records.filter { it.system == RinexNavigationSystem.BEIDOU }.groupBy { it.svid }
        .mapValues { (_, rows) -> rows.map { record ->
            // The numeric week is already native BDT; this helper only reads and normalizes
            // the adjusted transmission fields, without a physical GPS/BDT conversion.
            val transmission = record.gpsAlignedTransmission()?.let { BeiDouTime(it.week, it.secondsOfWeek) }
            var identity: BeiDouSatelliteIdentity? = null
            var epoch: Long? = null
            val decoded = runCatching {
                val clock = timeScale.getOrThrow()
                val fields = RinexOrbitFields(record)
                val nominal = RinexNativeEpoch.gps(record.epoch)
                val toc = BeiDouTime(nominal.week - 1356, nominal.secondsOfWeek)
                val toe = BeiDouTime(fields.integer(4, 2, 0..Int.MAX_VALUE), fields.orbit(2, 0))
                val tocUtc = requireNotNull(clock.utcAt(toc)) { "BDT clock epoch outside time evidence" }
                epoch = requireNotNull(clock.utcAt(toe)) { "BDT orbit epoch outside time evidence" }
                identity = requireNotNull(catalog.identityAt(record.svid, tocUtc)) { "Unknown dated BeiDou SVN/orbit class" }
                require(catalog.identityAt(record.svid, requireNotNull(epoch)) == identity) { "BeiDou identity changed between toe/toc" }
                val transmittedUtc = requireNotNull(transmission?.let { clock.utcAt(it) }) { "BDT upload time unavailable" }
                require(catalog.identityAt(record.svid, transmittedUtc) == identity) { "BeiDou upload crosses identity boundary" }
                RinexBeiDouImporter.decode(record, requireNotNull(identity.orbitType),
                    toe.plusSeconds(-policy.maximumEphemerisDistanceSeconds), toe.plusSeconds(policy.maximumEphemerisDistanceSeconds))
            }.getOrElse { diagnostics += "BeiDou:${record.svid}: ${it.message}"; null }
            Upload(transmission, decoded, identity, epoch)
        } }

    init { require(provenance.kind == kind) }

    override fun positions(request: GnssSkyRequest): GnssOrbitBatch {
        fun unavailable(reason: String) = GnssOrbitBatch(emptyList(), diagnostics + reason, reserved)
        if (!policy.allowAlignedFrameApproximation)
            return unavailable("BeiDou: approximate BDCS/WGS84 sky alignment was not permitted")
        val clock = timeScale.getOrNull() ?: return unavailable("BeiDou: missing, damaged or conflicting BDUT correction")
        val time = clock.atUtcMillis(request.atMillis) ?: return unavailable("BeiDou: UTC/BDUT evidence outside application age limit")
        val messages = diagnostics.toMutableList()
        messages += "BeiDou orbit class catalog: ${catalog.description}"
        messages += "BeiDou: approximate BDCS/WGS84 sky axes; ephemeris and BDUT age bounded by application policy, not an inferred broadcast fit"
        val blocked = mutableSetOf<Pair<Constellation, Int>>()
        fun applicable(upload: Upload): Boolean = upload.decoded?.ephemeris?.let {
            time.secondsSince(it.validFrom) >= 0 && it.validUntil.secondsSince(time) >= 0
        } == true
        val positions = records.mapNotNull { (svid, candidates) ->
            val currentIdentity = catalog.identityAt(svid, request.atMillis)
            if (currentIdentity == null || candidates.any { it.transmitted == null }) {
                messages += "BeiDou:$svid: dated identity/catalog age or upload ordering unavailable"
                return@mapNotNull null
            }
            val transmitted = candidates.filter { time.secondsSince(requireNotNull(it.transmitted)) >= 0 }
            val latestTime = transmitted.maxByOrNull { requireNotNull(it.transmitted).let { t -> t.week * 604800.0 + t.secondsOfWeek } }?.transmitted
            val latest = transmitted.filter { it.transmitted == latestTime }.distinct()
            if (latest.any { it.identity == currentIdentity && applicable(it) && it.decoded?.health != 0 })
                blocked += Constellation.BEIDOU to svid
            val selected = latest.singleOrNull()
            val message = selected?.decoded
            if (selected == null || !applicable(selected) || selected.identity != currentIdentity || message == null || message.health != 0) {
                messages += "BeiDou:$svid: no unique applicable healthy B1I upload for the current SVN"
                return@mapNotNull null
            }
            val state = BeiDouBroadcastPropagator.propagate(message.ephemeris, time).state
            if (state == null) {
                messages += "BeiDou:$svid: propagation unavailable"
                null
            } else GnssOrbitPosition(Constellation.BEIDOU, svid,
                EcefPosition(state.xMeters, state.yMeters, state.zMeters),
                provenance.copy(referenceEpochMillis = requireNotNull(selected.referenceUtcMillis)),
                hasEphemeris = true, hasAlmanac = false, carrierHz = 1_561_098_000f)
        }
        return GnssOrbitBatch(positions, messages, reserved, blocked)
    }
}
