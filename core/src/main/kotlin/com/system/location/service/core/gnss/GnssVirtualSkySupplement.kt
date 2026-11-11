package com.system.location.service.core.gnss

import kotlin.math.*

/** Supplied by the output adapters after verification, not inferred from a UI slider. */
data class GnssOutputLimits(val maxVisible: Int, val maxPerTalker: Int, val maxUsed: Int) {
    init { require(maxVisible > 0 && maxPerTalker > 0 && maxUsed in 1..minOf(maxVisible, 99)) }
}

data class GnssVirtualSupplementResult(
    val positions: List<GnssOrbitPosition>,
    val availableCapacity: Int,
    val diagnostics: List<String>,
)

/**
 * Explicit observer-relative hypothetical sky for quantity tests. This is not a Walker orbit
 * or a claim about actual in-orbit satellites. Reserved identities include hidden orbital
 * records, so supplementation never lifts one of those records above the horizon.
 */
class GnssVirtualSkySupplement(
    identities: List<Pair<Constellation, Int>>,
    val outputLimits: GnssOutputLimits,
    private val seed: Long,
) {
    private val identities = identities.toList()
    private val provenance = GnssOrbitProvenance("virtual-quantity-sky", "1",
        "Portal observer-relative hypothetical quantity scenario", 0, GnssOrbitSourceKind.VIRTUAL)

    init {
        require(identities.distinct().size == identities.size)
        require(identities.all { (constellation, svid) -> GnssSatelliteIds.nmeaId(constellation, svid) != null })
    }

    fun supplement(request: GnssSkyRequest, visible: List<GnssOrbitPosition>,
        reserved: Set<Pair<Constellation, Int>>, minimum: Int): GnssVirtualSupplementResult {
        require(minimum >= 0)
        require(visible.map { it.constellation to it.svid }.distinct().size == visible.size)
        val occupied = reserved + visible.map { it.constellation to it.svid }
        val counts = visible.groupingBy { talker(it.constellation) }.eachCount().toMutableMap()
        if (visible.size > outputLimits.maxVisible || counts.values.any { it > outputLimits.maxPerTalker })
            return GnssVirtualSupplementResult(emptyList(), visible.size,
                listOf("原始天空超出已验证输出限制，不能执行数量保证；保留原始天空供诊断"))
        val free = identities.filter { it !in occupied }.filter { identity ->
            val group = talker(identity.first)
            val count = counts[group] ?: 0
            if (count >= outputLimits.maxPerTalker) false else { counts[group] = count + 1; true }
        }
        val capacity = minOf(outputLimits.maxVisible, visible.size + free.size)
        if (minimum > capacity) return GnssVirtualSupplementResult(emptyList(), capacity,
            listOf("无法保证卫星下限 $minimum：当前身份空间和输出限制最多支持 $capacity 颗；保留原始天空"))
        val shortage = (minimum - visible.size).coerceAtLeast(0)
        val additions = free.take(shortage).map { identity -> position(request, identity) }
        val diagnostics = if (additions.isEmpty()) emptyList() else
            listOf("数量保证：增加 ${additions.size} 颗观察者局部虚拟卫星，不代表真实轨道")
        return GnssVirtualSupplementResult(additions, capacity, diagnostics)
    }

    private fun position(request: GnssSkyRequest, identity: Pair<Constellation, Int>): GnssOrbitPosition {
        val index = identities.indexOf(identity)
        val phase = java.util.Random(seed).nextDouble() * 2 * PI
        val longitude = Math.toRadians(request.observer.longitude)
        val latitude = Math.toRadians(request.observer.latitude)
        val azimuth = phase + index * PI * (3 - sqrt(5.0)) +
            (request.atMillis % 43_200_000) / 43_200_000.0 * 2 * PI + 0.5 * sin(longitude)
        val elevation = Math.toRadians(15 + 65 * (0.5 + 0.5 * sin(index * 1.71 + phase +
            (request.atMillis % 50_400_000) / 50_400_000.0 * 2 * PI + 0.3 * sin(latitude))))
        val range = 23_000_000.0
        val east = range * cos(elevation) * sin(azimuth)
        val north = range * cos(elevation) * cos(azimuth)
        val up = range * sin(elevation)
        val origin = GnssObserverGeometry.ecef(request.observer, request.altitudeMeters)
        val position = EcefPosition(
            origin.xMeters - sin(longitude) * east - sin(latitude) * cos(longitude) * north + cos(latitude) * cos(longitude) * up,
            origin.yMeters + cos(longitude) * east - sin(latitude) * sin(longitude) * north + cos(latitude) * sin(longitude) * up,
            origin.zMeters + cos(latitude) * north + sin(latitude) * up,
        )
        return GnssOrbitPosition(identity.first, identity.second, position, provenance,
            hasEphemeris = false, hasAlmanac = false)
    }

    private fun talker(constellation: Constellation) = when (constellation) {
        Constellation.SBAS -> Constellation.GPS
        else -> constellation
    }
}
