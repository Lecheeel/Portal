package com.system.location.service.core.gnss

import java.time.ZoneOffset
import kotlin.math.abs

/** Source-era evidence and explicit sky-only frame approximation, never inferred from slot. */
data class RinexGlonassSkyPolicy(
    val pz9011OperationalFromUtcMillis: Long,
    val allowApproximateItrfAlignment: Boolean,
) {
    init { require(pz9011OperationalFromUtcMillis >= 0) }
}

/**
 * RINEX 3 A10 UTC epochs -> continuous SI elapsed seconds -> native GLONASS RK4.
 * UTC labels are not UTC+3 and POSIX subtraction is not used for integration across a leap.
 * Nominal UTC is used for sky timing; fractional UTC(SU)/UTC corrections, ranging/clock
 * observables and exact contemporary WGS84 realization transformations are outside this adapter.
 */
class RinexGlonassOrbitProvider(file: RinexNavigationFile, private val provenance: GnssOrbitProvenance,
    private val utc: GnssUtcTimeScale, private val policy: RinexGlonassSkyPolicy) : GnssOrbitProvider {
    override val kind = GnssOrbitSourceKind.BROADCAST_EPHEMERIS
    private val diagnostics = mutableListOf<String>()
    private val reserved = file.records.filter { it.system == RinexNavigationSystem.GLONASS }
        .mapNotNull { r -> GnssSatelliteIds.nmeaId(Constellation.GLONASS, r.svid)?.let { Constellation.GLONASS to r.svid } }.toSet()
    private data class Upload(val referenceUtcMillis: Long?, val reference: GpsTime?,
        val transmitted: GpsTime?, val decoded: RinexGlonassBroadcast?)
    private val records = file.records.filter { it.system == RinexNavigationSystem.GLONASS }.groupBy { it.svid }
        .mapValues { (_, rows) -> rows.map { record ->
            val referenceMillis = runCatching {
                require(record.epoch.second < 60) { "UTC leap-second label is not representable in POSIX" }
                record.epoch.date.atTime(record.epoch.hour, record.epoch.minute, record.epoch.second)
                    .toInstant(ZoneOffset.UTC).toEpochMilli()
            }.getOrNull()
            val reference = referenceMillis?.let { utc.gpsAtUtcMillis(it).time }
            val transmission = runCatching {
                val frame = requireNotNull(record.clock.getOrNull(2))
                require(frame.isFinite() && frame >= 0 && frame < 604800 && frame == frame.toLong().toDouble())
                val sunday = record.epoch.date.minusDays((record.epoch.date.dayOfWeek.value % 7).toLong())
                // Resolve the nearest civil week to tb, not the week containing the request.
                // This disambiguates Saturday/Sunday uploads without reviving data next week.
                requireNotNull(reference)
                (-1..1).mapNotNull { shift ->
                    val stamp = sunday.plusWeeks(shift.toLong()).atStartOfDay().toInstant(ZoneOffset.UTC)
                        .toEpochMilli() + frame.toLong() * 1000
                    utc.gpsAtUtcMillis(stamp).time
                }.minByOrNull { abs(it.secondsSince(reference)) }
            }.getOrNull()
            val decoded = runCatching { RinexGlonassImporter.decode(record, GlonassReferenceFrame.PZ_90_11) }
                .getOrElse { diagnostics += "GLONASS:${record.svid}: ${it.message}"; null }
            Upload(referenceMillis, reference, transmission, decoded)
        } }

    init { require(provenance.kind == kind) }

    override fun positions(request: GnssSkyRequest): GnssOrbitBatch {
        fun unavailable(reason: String) = GnssOrbitBatch(emptyList(), diagnostics + reason, reserved)
        if (!policy.allowApproximateItrfAlignment)
            return unavailable("GLONASS: approximate ITRF2008/WGS84 alignment was not permitted")
        val conversion = utc.gpsAtUtcMillis(request.atMillis)
        val time = conversion.time ?: return unavailable("GLONASS: ${conversion.reason}")
        val messages = diagnostics.toMutableList()
        messages += "GLONASS: PZ-90.11 via EPSG:7960 (epoch 2010.0), approximate ITRF2008/WGS84 sky axes and nominal UTC timing"
        val blocked = mutableSetOf<Pair<Constellation, Int>>()
        fun applicable(upload: Upload): Boolean = upload.referenceUtcMillis != null && upload.reference != null &&
            upload.transmitted != null && upload.referenceUtcMillis >= policy.pz9011OperationalFromUtcMillis &&
            abs(time.secondsSince(upload.reference)) < 900 &&
            // Frame starts can be exactly 15 minutes before tb; this is distinct from the
            // propagator's strict |t-tb| < 15-minute integration applicability.
            abs(upload.transmitted.secondsSince(upload.reference)) <= 900
        fun unhealthy(message: RinexGlonassBroadcast): Boolean {
            // RINEX 3.05 GLO-M/K-only flags are meaningful only when M (bits 7-8) is 01.
            val modern = message.statusFlags?.let { (it ushr 7) and 3 == 1 } == true
            val flags = message.healthFlags
            return message.unhealthy || (modern && flags != null &&
                (flags and 4 != 0 || (flags and 2 != 0 && flags and 1 == 0)))
        }
        val positions = records.mapNotNull { (svid, candidates) ->
            if (GnssSatelliteIds.nmeaId(Constellation.GLONASS, svid) == null) {
                messages += "GLONASS:$svid: unsupported output slot identity"
                return@mapNotNull null
            }
            if (candidates.any { it.transmitted == null }) {
                messages += "GLONASS:$svid: upload ordering unavailable; no older broadcast may be revived"
                return@mapNotNull null
            }
            val transmitted = candidates.filter { time.secondsSince(requireNotNull(it.transmitted)) >= 0 }
            val latestTime = transmitted.maxByOrNull { requireNotNull(it.transmitted).let { t -> t.week * 604800.0 + t.secondsOfWeek } }?.transmitted
            val latest = transmitted.filter { it.transmitted == latestTime }.distinct()
            if (latest.any { applicable(it) && it.decoded?.let(::unhealthy) == true }) blocked += Constellation.GLONASS to svid
            val selected = latest.singleOrNull()
            val message = selected?.decoded
            if (selected == null || !applicable(selected) || message == null || unhealthy(message) ||
                message.frequencyNumber !in -7..6 || GnssSatelliteIds.nmeaId(Constellation.GLONASS, svid) == null) {
                messages += "GLONASS:$svid: no unique applicable healthy L1 FDMA upload in supported PZ-90.11 era"
                return@mapNotNull null
            }
            val state = GlonassBroadcastPropagator.propagate(message.orbit,
                time.secondsSince(requireNotNull(selected.reference))).state
            if (state == null) {
                messages += "GLONASS:$svid: integration unavailable"
                null
            } else GnssOrbitPosition(Constellation.GLONASS, svid,
                GlonassSkyFrameApproximation.pz9011ToItrf2008(state.positionMeters),
                provenance.copy(referenceEpochMillis = requireNotNull(selected.referenceUtcMillis)),
                hasEphemeris = true, hasAlmanac = false,
                carrierHz = (1_602_000_000.0 + message.frequencyNumber * 562_500.0).toFloat())
        }
        return GnssOrbitBatch(positions, messages, reserved, blocked)
    }
}
