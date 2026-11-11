package com.system.location.service.core.gnss

import java.time.ZoneOffset
import kotlin.math.abs

/** Native system calendar conversion. This deliberately does not add UTC leap seconds. */
internal object RinexNativeEpoch {
    fun gps(epoch: RinexNavigationEpoch): GpsTime {
        require(epoch.second < 60) { "Leap-second label is not a GPS calendar epoch" }
        val seconds = epoch.date.atTime(epoch.hour, epoch.minute, epoch.second).toEpochSecond(ZoneOffset.UTC) -
            GnssUtcTimeScale.GPS_EPOCH_MILLIS / 1000
        require(seconds >= 0 && seconds / 604800 <= Int.MAX_VALUE)
        return GpsTime((seconds / 604800).toInt(), (seconds % 604800).toDouble())
    }
}

/** RINEX numeric presence/range checks are independent of the text parser. */
internal class RinexOrbitFields(private val record: RinexNavigationRecord) {
    fun orbit(row: Int, column: Int): Double = requireNotNull(record.orbit.getOrNull(row)?.getOrNull(column)) {
        "Missing orbit field ${row + 1}/${column + 1}"
    }.also { require(it.isFinite()) }
    fun clock(column: Int): Double = requireNotNull(record.clock.getOrNull(column)) { "Missing clock field" }
        .also { require(it.isFinite()) }
    fun integer(row: Int, column: Int, range: IntRange): Int {
        val value = orbit(row, column)
        require(value >= range.first && value <= range.last && value == value.toInt().toDouble()) {
            "Invalid integer orbit field ${row + 1}/${column + 1}"
        }
        return value.toInt()
    }
}

data class RinexGpsBroadcast(val ephemeris: GpsLnavEphemeris, val availableFrom: GpsTime)

/** IS-GPS-200N fit duration is supplied by RINEX A6 in HOURS, not a one-bit flag. */
object RinexGpsImporter {
    fun decode(record: RinexNavigationRecord): RinexGpsBroadcast {
        require(record.system == RinexNavigationSystem.GPS && record.orbit.size == 7)
        val f = RinexOrbitFields(record)
        val toc = RinexNativeEpoch.gps(record.epoch)
        val toe = GpsTime(f.integer(4, 2, 0..Int.MAX_VALUE), f.orbit(2, 0))
        require(abs(toc.secondsSince(toe)) <= 302400) { "GPS calendar/full-week mismatch" }
        val fitHours = f.orbit(6, 1)
        require(fitHours > 0) { "Unknown GPS fit duration" }
        val from = toe.plusSeconds(-fitHours * 1800)
        val until = toe.plusSeconds(fitHours * 1800)
        // RINEX already adjusts transmission seconds relative to the reported full week.
        val transmissionSeconds = f.orbit(6, 0)
        require(transmissionSeconds >= -604800 && transmissionSeconds < 1209600) {
            "Missing or out-of-range GPS transmission time"
        }
        val available = GpsTime(toe.week, 0.0).plusSeconds(transmissionSeconds)
        require(available.secondsSince(until) <= 0 && abs(available.secondsSince(toe)) <= 604800) {
            "GPS transmission time inconsistent with ephemeris"
        }
        val iode = f.integer(0, 0, 0..255)
        val e = GpsLnavEphemeris(record.svid, toe, toc, from, until, iode, iode,
            f.integer(5, 3, 0..1023), f.integer(5, 1, 0..63),
            f.orbit(1, 3), f.orbit(1, 1), f.orbit(3, 0), f.orbit(2, 2), f.orbit(3, 2), f.orbit(0, 3),
            f.orbit(0, 2), f.orbit(3, 3), f.orbit(4, 0),
            f.orbit(1, 0), f.orbit(1, 2), f.orbit(3, 1), f.orbit(0, 1), f.orbit(2, 1), f.orbit(2, 3),
            f.clock(0), f.clock(1), f.clock(2), f.orbit(5, 2))
        require(toc == toe) { "GPS LNAV clock/orbit reference epochs differ" }
        return RinexGpsBroadcast(e, available)
    }
}

/** Native GPS orbit at the requested system epoch; approximate sky geometry, not observables. */
class RinexGpsOrbitProvider(file: RinexNavigationFile, private val provenance: GnssOrbitProvenance,
    private val utc: GnssUtcTimeScale) : GnssOrbitProvider {
    override val kind = GnssOrbitSourceKind.BROADCAST_EPHEMERIS
    private val reserved = file.records.filter { it.system == RinexNavigationSystem.GPS && it.svid in 1..32 }
        .map { Constellation.GPS to it.svid }.toSet()
    private val diagnostics = mutableListOf<String>()
    private val records = file.records.filter { it.system == RinexNavigationSystem.GPS }.mapNotNull { record ->
        runCatching { RinexGpsImporter.decode(record) }.getOrElse {
            diagnostics += "GPS:${record.svid}: ${it.message}"
            null
        }
    }.groupBy { it.ephemeris.svid }

    init { require(provenance.kind == kind) }

    override fun positions(request: GnssSkyRequest): GnssOrbitBatch {
        val conversion = utc.gpsAtUtcMillis(request.atMillis)
        val time = conversion.time ?: return GnssOrbitBatch(emptyList(),
            diagnostics + requireNotNull(conversion.reason), reserved)
        val messages = diagnostics.toMutableList()
        val blocked = mutableSetOf<Pair<Constellation, Int>>()
        val positions = records.mapNotNull { (svid, candidates) ->
            // Do not look ahead to a future transmission or revive a prior healthy upload after
            // a newer applicable unhealthy one. Expired uploads can fall back to other sources.
            val transmitted = candidates.filter { time.secondsSince(it.availableFrom) >= 0 }
            val latestTime = transmitted.maxByOrNull {
                it.availableFrom.week * 604800.0 + it.availableFrom.secondsOfWeek
            }?.availableFrom
            val latest = transmitted.filter { it.availableFrom == latestTime }.distinct()
            val accepted = latest.singleOrNull()
            if (accepted == null || time.secondsSince(accepted.ephemeris.validFrom) < 0 ||
                accepted.ephemeris.validUntil.secondsSince(time) < 0) {
                messages += "GPS:$svid: no applicable RINEX broadcast upload"
                return@mapNotNull null
            }
            if (accepted.ephemeris.health != 0) blocked += Constellation.GPS to svid
            val result = GpsBroadcastPropagator.propagate(accepted.ephemeris, time)
            val state = result.state
            if (state == null) {
                messages += "GPS:$svid: ${result.reason}"
                null
            } else GnssOrbitPosition(Constellation.GPS, svid,
                EcefPosition(state.xMeters, state.yMeters, state.zMeters), provenance,
                hasEphemeris = true, hasAlmanac = false)
        }
        return GnssOrbitBatch(positions, messages, reserved, blocked)
    }
}
