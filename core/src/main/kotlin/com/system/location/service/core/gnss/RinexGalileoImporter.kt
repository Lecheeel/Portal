package com.system.location.service.core.gnss

import kotlin.math.abs

/** Only the signals actually represented by this RINEX navigation message are reported. */
data class RinexGalileoService(
    val e1bHealthy: Boolean?,
    val e5aHealthy: Boolean?,
    val e5bHealthy: Boolean?,
    val accuracyAvailable: Boolean,
)

data class RinexGalileoBroadcast(
    val ephemeris: GalileoBroadcastEphemeris,
    val availableFrom: GalileoTime,
    val service: RinexGalileoService,
    val bgdE5aE1Seconds: Double,
    val bgdE5bE1Seconds: Double?,
)

/**
 * RINEX 3.05 Table A8. Parsing a native GST epoch establishes no GPS–GST offset or GTRF
 * transformation; a provider must independently supply those before combining other systems.
 */
object RinexGalileoImporter {
    fun decode(record: RinexNavigationRecord): RinexGalileoBroadcast {
        require(record.system == RinexNavigationSystem.GALILEO && record.orbit.size == 7)
        val f = RinexOrbitFields(record)
        val nominal = RinexNativeEpoch.gps(record.epoch)
        // Calendar origin relation only. The record's calendar is already GAL, never UTC/GPS.
        val toc = GalileoTime(nominal.week - 1024, nominal.secondsOfWeek)
        val toe = GalileoTime(f.integer(4, 2, 1024..Int.MAX_VALUE) - 1024, f.orbit(2, 0))
        require(abs(toc.secondsSince(toe)) <= 302400) { "Galileo calendar/full-week mismatch" }
        val source = f.integer(4, 1, 0..1023)
        require(source and 0x31f.inv() == 0) { "Unknown Galileo data source bits" }
        val format = source and 7
        val reference = when {
            format in listOf(1, 4, 5) && source and 0x300 == 0x200 -> GalileoClockReference.INAV_E1_E5B
            format == 2 && source and 0x300 == 0x100 -> GalileoClockReference.FNAV_E1_E5A
            else -> throw IllegalArgumentException("Inconsistent Galileo message and clock source")
        }
        val health = f.integer(5, 1, 0..511)
        val inav = reference == GalileoClockReference.INAV_E1_E5B
        val sisa = f.orbit(5, 0)
        require(sisa >= 0 || sisa == -1.0) { "Invalid Galileo SISA" }
        val service = RinexGalileoService(
            e1bHealthy = if (inav) health and 7 == 0 else null,
            e5aHealthy = if (!inav) health and 0x38 == 0 else null,
            e5bHealthy = if (inav) health and 0x1c0 == 0 else null,
            accuracyAvailable = sisa != -1.0)
        val transmissionSeconds = f.orbit(6, 0)
        require(transmissionSeconds >= -604800 && transmissionSeconds < 1209600) {
            "Missing or out-of-range Galileo transmission time"
        }
        val available = GalileoTime(toe.week, 0.0).plusSeconds(transmissionSeconds)
        val interval = requireNotNull(GalileoEphemerisValidity.resolve(toe))
        require(available.secondsSince(interval.until) <= 0 && abs(available.secondsSince(toe)) <= 604800) {
            "Galileo transmission time inconsistent with ephemeris"
        }
        val e = GalileoBroadcastEphemeris(record.svid, toe, toc, interval.from, interval.until,
            f.integer(0, 0, 0..1023), reference,
            f.orbit(1, 3), f.orbit(1, 1), f.orbit(3, 0), f.orbit(2, 2), f.orbit(3, 2), f.orbit(0, 3),
            f.orbit(0, 2), f.orbit(3, 3), f.orbit(4, 0),
            f.orbit(1, 0), f.orbit(1, 2), f.orbit(3, 1), f.orbit(0, 1), f.orbit(2, 1), f.orbit(2, 3),
            f.clock(0), f.clock(1), f.clock(2))
        return RinexGalileoBroadcast(e, available, service, f.orbit(5, 2),
            if (inav) f.orbit(5, 3) else null)
    }
}
