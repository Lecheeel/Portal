package com.system.location.service.core.gnss

import kotlin.math.abs

data class RinexBeiDouBroadcast(
    val ephemeris: BeiDouBroadcastEphemeris,
    val availableFrom: BeiDouTime,
    val health: Int,
    val accuracyMeters: Double,
    val tgd1Seconds: Double,
    val tgd2Seconds: Double,
    val ageOfDataClock: Int,
    val ageOfDataEphemeris: Int,
)

/**
 * RINEX 3.05 Table A14 carries neither an orbit-class field nor an absolute fit interval.
 * Both must be supplied independently. PRN, inclination and AODE/AODC are not substitutes.
 * Epochs are native BDT; no GPS/UTC clock conversion or BDCS datum transform is implied.
 */
object RinexBeiDouImporter {
    fun decode(record: RinexNavigationRecord, orbitType: BeiDouOrbitType,
        validFrom: BeiDouTime, validUntil: BeiDouTime): RinexBeiDouBroadcast {
        require(record.system == RinexNavigationSystem.BEIDOU && record.orbit.size == 7)
        require(validUntil.secondsSince(validFrom) > 0) { "Missing absolute BeiDou applicability" }
        val f = RinexOrbitFields(record)
        val nominal = RinexNativeEpoch.gps(record.epoch)
        // BDT calendar origin is 2006-01-01. This is an epoch-number relation, not GPS-BDT.
        val toc = BeiDouTime(nominal.week - 1356, nominal.secondsOfWeek)
        val toe = BeiDouTime(f.integer(4, 2, 0..Int.MAX_VALUE), f.orbit(2, 0))
        require(abs(toc.secondsSince(toe)) <= 302400) { "BeiDou calendar/full-week mismatch" }
        val transmissionSeconds = f.orbit(6, 0)
        require(transmissionSeconds >= -604800 && transmissionSeconds < 1209600) {
            "Missing or out-of-range BeiDou transmission time"
        }
        val available = BeiDouTime(toe.week, 0.0).plusSeconds(transmissionSeconds)
        require(available.secondsSince(validFrom) >= 0 && validUntil.secondsSince(available) >= 0) {
            "BeiDou transmission outside supplied applicability"
        }
        val e = BeiDouBroadcastEphemeris(record.svid, orbitType, toe, toc, validFrom, validUntil,
            f.orbit(1, 3), f.orbit(1, 1), f.orbit(3, 0), f.orbit(2, 2), f.orbit(3, 2), f.orbit(0, 3),
            f.orbit(0, 2), f.orbit(3, 3), f.orbit(4, 0),
            f.orbit(1, 0), f.orbit(1, 2), f.orbit(3, 1), f.orbit(0, 1), f.orbit(2, 1), f.orbit(2, 3),
            f.clock(0), f.clock(1), f.clock(2))
        val accuracy = f.orbit(5, 0)
        require(accuracy >= 0) { "Invalid BeiDou accuracy" }
        return RinexBeiDouBroadcast(e, available, f.integer(5, 1, 0..1), accuracy,
            f.orbit(5, 2), f.orbit(5, 3), f.integer(6, 1, 0..31), f.integer(0, 0, 0..31))
    }
}
