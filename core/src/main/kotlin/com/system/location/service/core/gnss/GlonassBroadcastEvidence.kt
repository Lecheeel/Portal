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
    @kotlinx.serialization.Transient
    private var cachedDomain: GlonassTimeDomain? = null

    init {
        require(bootCount >= 0 && domainId.isNotBlank() && domainId.length <= 256)
        require(elapsedRealtimeNanos >= 0)
        val from = coverageFrom.coordinate()
        val until = coverageUntil.coordinate()
        require(until.secondsSince(from) > 0.0)
        require(anchor.coordinate().secondsSince(from) >= 0.0)
        require(until.secondsSince(anchor.coordinate()) > 0.0)
    }

    @Synchronized
    fun domain(): GlonassTimeDomain = cachedDomain ?: GlonassTimeDomain(domainId,
        coverageFrom.coordinate(), coverageUntil.coordinate()).also { cachedDomain = it }

    fun at(elapsedNanos: Long, currentBootCount: Int?, timeDomain: GlonassTimeDomain = domain()): GlonassTime? {
        if (currentBootCount != bootCount || elapsedNanos < elapsedRealtimeNanos) return null
        val delta = (elapsedNanos - elapsedRealtimeNanos) / 1e9
        return runCatching { timeDomain.at(anchor.coordinate())?.plusSeconds(delta) }.getOrNull()
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

/**
 * Independently established applicability of BOTH immediate and almanac health evidence.
 * This interval is not derived from tb, En, P1 or the orbital integration limit.
 */
@Serializable
data class GlonassServiceEvidence(
    val broadcastAt: GlonassCoordinateEvidence,
    val validFrom: GlonassCoordinateEvidence,
    val validUntil: GlonassCoordinateEvidence,
    val almanacHealthCn: Int,
) {
    init {
        require(almanacHealthCn in 0..1)
        require(broadcastAt.coordinate().secondsSince(validFrom.coordinate()) >= 0.0)
        require(validUntil.coordinate().secondsSince(broadcastAt.coordinate()) > 0.0)
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
    /** Missing legacy evidence remains decodable but cannot authorize physical sky output. */
    val service: GlonassServiceEvidence? = null,
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

    fun serviceRejection(transmissionTime: GlonassTime): String? {
        // ICD 5.1 §4.5: only the MSB of Bn is a malfunction flag; the two LSBs are ignored.
        if (healthBn and 4 != 0 || healthLn != 0) return "即时健康标志不允许导航使用"
        if (accuracyFt == 15) return "FT=15 未定义测距精度"
        val evidence = service ?: return "缺少独立健康适用期和年历 Cn 证据"
        if (evidence.almanacHealthCn != 1) return "年历 Cn 表明卫星不可用"
        val domain = transmissionTime.domain
        val from = domain.at(evidence.validFrom.coordinate()) ?: return "健康适用期不在当前连续时钟域内"
        val until = domain.at(evidence.validUntil.coordinate()) ?: return "健康适用期不在当前连续时钟域内"
        if (domain.at(evidence.broadcastAt.coordinate()) == null) return "健康广播标签不在当前连续时钟域内"
        return if (transmissionTime.secondsSince(from)!! < 0.0 || transmissionTime.secondsSince(until)!! >= 0.0)
            "发射时刻超出独立健康适用期" else null
    }
}

/**
 * GLONASS broadcast geometry requires a real continuous clock, datum realization and receiver
 * transform. If any of these are absent this evidence remains available for diagnostics only.
 */
@Serializable
data class GlonassBroadcastEvidence(
    val clock: GlonassElapsedClockEvidence,
    val receiverFrame: GlonassReferenceFrame,
    /** Legacy archive field: constant latitude offsets cannot resolve a moving route's normal. */
    val receiverTransform: GlonassEcefTransform? = null,
    val ephemerides: List<GlonassBroadcastEphemerisEvidence>,
    val datumTransform: GnssReceiverDatumTransform? = null,
) {
    init {
        require(datumTransform == null || datumTransform.bootCount == clock.bootCount)
        require(ephemerides.isNotEmpty() && ephemerides.size <= 31)
        require(ephemerides.map { it.orbit.slot }.distinct().size == ephemerides.size)
        val domain = clock.domain()
        require(ephemerides.all { it.orbit.referenceFrame == receiverFrame && it.resolve(domain) != null })
    }

    val identities: Set<Int> get() = ephemerides.map { it.orbit.slot }.toSet()
}
