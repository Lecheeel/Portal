package com.system.location.service.detector.model

import kotlin.math.abs

/** Independent receiver-window statistics. No model from Portal is used by the detector. */
data class GnssWindowProfile(
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val receiverSeconds: Int,
    val maximumGapMillis: Long,
    val visibleCounts: Map<Int, Int>,
    val constellationCounts: Map<Int, Int>,
    val usedCount: Int,
    /** Per-signal joint bins (constellation, 5 degree elevation, 5 dB-Hz CN0). */
    val signalBins: Map<Int, Int>,
) {
    val satelliteCount: Int get() = constellationCounts.values.sum()
    val meanVisible: Double get() = satelliteCount.toDouble() / receiverSeconds
    val usedFraction: Double? get() = if (satelliteCount == 0) null else usedCount.toDouble() / satelliteCount
    val adequate: Boolean get() = receiverSeconds >= 20 && endedAtMillis - startedAtMillis >= 30_000L &&
        maximumGapMillis <= 2_000L && satelliteCount > 0

    companion object {
        fun from(evidence: GnssEvidenceSnapshot): GnssWindowProfile {
            require(evidence.startedAtMillis >= 0 && evidence.endedAtMillis >= evidence.startedAtMillis)
            require(evidence.frames.size in 1..3000) { "需要 1–3000 帧 GNSS 状态" }
            var previous = -1L
            val seconds = sortedMapOf<Long, GnssFrameSnapshot>()
            evidence.frames.forEach { frame ->
                require(frame.capturedAtMillis in evidence.startedAtMillis..evidence.endedAtMillis &&
                    frame.capturedAtMillis >= previous) { "GNSS 帧时间倒退或超出采样窗口" }
                require(frame.satellites.size <= 512)
                previous = frame.capturedAtMillis
                frame.satellites.forEach { s ->
                    require(s.constellation in 0..7 && s.svid in 1..999 &&
                        s.cn0DbHz.isFinite() && s.cn0DbHz in 0f..63f &&
                        s.elevationDegrees.isFinite() && s.elevationDegrees in 0f..90f &&
                        s.azimuthDegrees.isFinite() && s.azimuthDegrees >= 0f && s.azimuthDegrees < 360f) {
                        "GNSS 字段无效，不能用于画像比较"
                    }
                }
                // Last arrival per UTC second: callback bursts do not increase statistical weight.
                seconds[frame.capturedAtMillis / 1000] = frame
            }
            val frames = seconds.values.toList()
            val counts = mutableMapOf<Int, Int>()
            val systems = mutableMapOf<Int, Int>()
            val bins = mutableMapOf<Int, Int>()
            var used = 0
            frames.forEach { frame ->
                val identities = frame.satellites.groupBy { it.constellation to it.svid }
                counts.increment(identities.size)
                identities.forEach { (identity, signals) ->
                    systems.increment(identity.first)
                    if (signals.any { it.usedInFix }) used++
                }
                frame.satellites.forEach { s ->
                    val elevation = minOf(17, (s.elevationDegrees / 5).toInt())
                    val cn0 = (s.cn0DbHz / 5).toInt()
                    bins.increment((s.constellation * 18 + elevation) * 13 + cn0)
                }
            }
            return GnssWindowProfile(frames.first().capturedAtMillis, frames.last().capturedAtMillis,
                frames.size, frames.zipWithNext { a, b -> b.capturedAtMillis - a.capturedAtMillis }.maxOrNull() ?: 0L,
                counts.toMap(), systems.toMap(), used, bins.toMap())
        }

        private fun MutableMap<Int, Int>.increment(key: Int) { this[key] = (this[key] ?: 0) + 1 }
    }
}

data class GnssProfileDifference(
    val visibleCountTotalVariation: Double?,
    val constellationTotalVariation: Double?,
    val signalJointTotalVariation: Double?,
    val meanVisibleDelta: Double,
    val usedFractionDelta: Double?,
    val constellationRatioDeltas: Map<Int, Double>?,
    val adequateWindows: Boolean,
)

object GnssProfileComparison {
    /** TV(P,Q) = 1/2 sum_k |P(k)-Q(k)|; an empty population has no distribution. */
    private fun distance(a: Map<Int, Int>, b: Map<Int, Int>): Double? {
        val na = a.values.sum().toDouble()
        val nb = b.values.sum().toDouble()
        if (na == 0.0 || nb == 0.0) return null
        return (a.keys + b.keys).sumOf { abs((a[it] ?: 0) / na - (b[it] ?: 0) / nb) } / 2
    }

    fun compare(reference: GnssWindowProfile, trial: GnssWindowProfile): GnssProfileDifference {
        val a = reference.usedFraction
        val b = trial.usedFraction
        val ratios = if (reference.satelliteCount == 0 || trial.satelliteCount == 0) null else
            (reference.constellationCounts.keys + trial.constellationCounts.keys).associateWith {
                (trial.constellationCounts[it] ?: 0).toDouble() / trial.satelliteCount -
                    (reference.constellationCounts[it] ?: 0).toDouble() / reference.satelliteCount
            }
        return GnssProfileDifference(distance(reference.visibleCounts, trial.visibleCounts),
            distance(reference.constellationCounts, trial.constellationCounts),
            distance(reference.signalBins, trial.signalBins), trial.meanVisible - reference.meanVisible,
            if (a == null || b == null) null else b - a, ratios, reference.adequate && trial.adequate)
    }
}
