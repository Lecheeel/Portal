package com.system.location.service.core.gnss

import kotlinx.serialization.Serializable

@Serializable
enum class BeiDouReceiverFrame { BDCS }

@Serializable
data class BeiDouElapsedClockEvidence(
    val bootCount: Int,
    val week: Int,
    val secondsOfWeek: Double,
    val elapsedRealtimeNanos: Long,
) {
    init {
        require(bootCount >= 0 && elapsedRealtimeNanos >= 0)
        BeiDouTime(week, secondsOfWeek)
    }

    fun at(elapsedNanos: Long, currentBootCount: Int?): BeiDouTime? {
        if (currentBootCount != bootCount || elapsedNanos < elapsedRealtimeNanos) return null
        return runCatching { BeiDouTime(week, secondsOfWeek).plusSeconds((elapsedNanos - elapsedRealtimeNanos) / 1e9) }
            .getOrNull()
    }
}

/** Explicit BDT evidence; unlike GPS/Galileo it never assumes an inter-system offset. */
@Serializable
data class BeiDouBroadcastEvidence(
    val clock: BeiDouElapsedClockEvidence,
    val receiverFrame: BeiDouReceiverFrame,
    val serviceValidity: BeiDouServiceValidity,
    val d1: List<BeiDouD1Decoded> = emptyList(),
    val d2: List<BeiDouD2Decoded> = emptyList(),
) {
    init {
        require(d1.isNotEmpty() || d2.isNotEmpty()) { "BeiDou evidence is empty" }
        require(d1.map { it.ephemeris.svid }.distinct().size == d1.size)
        require(d2.map { it.ephemeris.svid }.distinct().size == d2.size)
        require((d1.map { it.ephemeris.svid } + d2.map { it.ephemeris.svid }).all { it in 1..63 })
    }

    val identities: Set<Int>
        get() = (d1.map { it.ephemeris.svid } + d2.map { it.ephemeris.svid }).toSet()
}
