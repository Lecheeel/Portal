package com.system.location.service.core.gnss

import java.time.LocalDate
import kotlin.math.abs
import kotlinx.serialization.Serializable

/** A serializable calendar coordinate; epoch days avoid a platform-specific LocalDate serializer. */
@Serializable
data class GlonassCoordinateEvidence(val epochDay: Long, val secondsOfDay: Double) {
    init {
        LocalDate.ofEpochDay(epochDay)
        require(secondsOfDay.isFinite() && secondsOfDay >= 0.0 && secondsOfDay < 86400.0)
    }

    fun coordinate(): GlonassCalendarCoordinate =
        GlonassCalendarCoordinate(LocalDate.ofEpochDay(epochDay), secondsOfDay)

    companion object {
        fun from(coordinate: GlonassCalendarCoordinate) =
            GlonassCoordinateEvidence(coordinate.date.toEpochDay(), coordinate.secondsOfDay)
    }
}

/**
 * Receiver clock evidence on the UTC(SU)+3h continuous GLONASS scale. The clock is deliberately
 * separate from Android wall time and GPS time; a missing or discontinuous domain cannot drive
 * orbital output.
 */
@Serializable
data class GlonassElapsedClockEvidence(
    val bootCount: Int,
    val domainId: String,
    val anchor: GlonassCoordinateEvidence,
    val elapsedRealtimeNanos: Long,
    val coverageFrom: GlonassCoordinateEvidence,
    val coverageUntil: GlonassCoordinateEvidence,
) {
    init {
        require(bootCount >= 0 && domainId.isNotBlank() && domainId.length <= 256)
        require(elapsedRealtimeNanos >= 0)
        val from = coverageFrom.coordinate()
        val until = coverageUntil.coordinate()
        require(until.secondsSince(from) > 0.0)
        require(anchor.coordinate().secondsSince(from) >= 0.0)
        require(until.secondsSince(anchor.coordinate()) > 0.0)
    }

    fun domain(): GlonassTimeDomain = GlonassTimeDomain(domainId,
        coverageFrom.coordinate(), coverageUntil.coordinate())

    fun at(elapsedNanos: Long, currentBootCount: Int?): GlonassTime? {
        if (currentBootCount != bootCount || elapsedNanos < elapsedRealtimeNanos) return null
        val delta = (elapsedNanos - elapsedRealtimeNanos) / 1e9
        return runCatching { domain().at(anchor.coordinate())?.plusSeconds(delta) }.getOrNull()
    }
}

/**
 * Explicit WGS-84 ECEF -> GLONASS ECEF transform. No default or datum-name substitution is
 * supplied. The matrix is row-major and must be a proper, near-orthonormal rotation; callers
 * obtain it from an independently validated datum solution.
 */
@Serializable
data class GlonassEcefTransform(
    val rotation: List<Double>,
    val translationMeters: GlonassCartesianVector,
    /** Datum transform's explicitly supplied geodetic orientation correction. */
    val latitudeOffsetDegrees: Double,
    val longitudeOffsetDegrees: Double,
) {
    init {
        require(rotation.size == 9 && rotation.all { it.isFinite() })
        require(listOf(translationMeters.x, translationMeters.y, translationMeters.z).all(Double::isFinite))
        require(latitudeOffsetDegrees.isFinite() && longitudeOffsetDegrees.isFinite())
        val r = rotation
        fun dot(a: Int, b: Int) = (0..2).sumOf { r[a * 3 + it] * r[b * 3 + it] }
        require((0..2).all { abs(dot(it, it) - 1.0) <= 1e-6 })
        require(abs(dot(0, 1)) <= 1e-6 && abs(dot(0, 2)) <= 1e-6 && abs(dot(1, 2)) <= 1e-6)
        val determinant = r[0] * (r[4] * r[8] - r[5] * r[7]) -
            r[1] * (r[3] * r[8] - r[5] * r[6]) + r[2] * (r[3] * r[7] - r[4] * r[6])
        require(abs(determinant - 1.0) <= 1e-5)
    }

    fun apply(position: EcefPosition): EcefPosition {
        val r = rotation
        return EcefPosition(
            r[0] * position.xMeters + r[1] * position.yMeters + r[2] * position.zMeters + translationMeters.x,
            r[3] * position.xMeters + r[4] * position.yMeters + r[5] * position.zMeters + translationMeters.y,
            r[6] * position.xMeters + r[7] * position.yMeters + r[8] * position.zMeters + translationMeters.z,
        )
    }
}

/** Compact, validated GLONASS evidence. It is never inferred from callback arrival timestamps. */
@Serializable
data class GlonassBroadcastEphemerisEvidence(
    val orbit: GlonassBroadcastStateVector,
    val referenceTime: GlonassCoordinateEvidence,
    val validFrom: GlonassCoordinateEvidence,
    val validUntil: GlonassCoordinateEvidence,
    val healthBn: Int,
    val healthLn: Int,
    val accuracyFt: Int,
    val ageDays: Int,
) {
    init {
        require(healthBn in 0..7 && healthLn in 0..1 && accuracyFt in 0..15 && ageDays in 0..31)
        val reference = referenceTime.coordinate()
        val from = validFrom.coordinate()
        val until = validUntil.coordinate()
        require(from.secondsSince(reference) > -900.0 && from.secondsSince(reference) <= 0.0)
        require(until.secondsSince(reference) >= 0.0 && until.secondsSince(reference) < 900.0 &&
            until.secondsSince(from) > 0.0)
    }

    fun resolve(domain: GlonassTimeDomain): GlonassOrbitalEphemeris? = runCatching {
        GlonassOrbitalEphemeris(orbit, domain.at(referenceTime.coordinate())!!,
            domain.at(validFrom.coordinate())!!, domain.at(validUntil.coordinate())!!)
    }.getOrNull()
}

/**
 * GLONASS broadcast geometry requires a real continuous clock, datum realization and receiver
 * transform. If any of these are absent this evidence remains available for diagnostics only.
 */
@Serializable
data class GlonassBroadcastEvidence(
    val clock: GlonassElapsedClockEvidence,
    val receiverFrame: GlonassReferenceFrame,
    val receiverTransform: GlonassEcefTransform,
    val ephemerides: List<GlonassBroadcastEphemerisEvidence>,
) {
    init {
        require(ephemerides.isNotEmpty() && ephemerides.size <= 31)
        require(ephemerides.map { it.orbit.slot }.distinct().size == ephemerides.size)
        val domain = clock.domain()
        require(ephemerides.all { it.orbit.referenceFrame == receiverFrame && it.resolve(domain) != null })
    }

    val identities: Set<Int> get() = ephemerides.map { it.orbit.slot }.toSet()
}
