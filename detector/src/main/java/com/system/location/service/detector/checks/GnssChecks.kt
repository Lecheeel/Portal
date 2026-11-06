package com.system.location.service.detector.checks

import android.location.GnssStatus
import com.system.location.service.detector.model.Check
import com.system.location.service.detector.model.CheckContext
import com.system.location.service.detector.model.CheckIds
import com.system.location.service.detector.model.CheckResult
import com.system.location.service.detector.model.GnssFrameSnapshot
import com.system.location.service.detector.model.GnssSatelliteSnapshot
import com.system.location.service.detector.model.SampleSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * C06 · GnssStatus 卫星统计。
 *
 * 期望：多星座（不只 BEIDOU）、卫星集合秒级稳定、CN0 随仰角合理、usedInFix 非随机；
 * 每次回调 shuffle 或全部同一星座均判 FAIL。
 *
 * 少于 [MIN_FRAMES] 帧时无法构成时间序列，返回 UNKNOWN（未回调/权限不足）。
 * 其余规则分四组，任一命中即 FAIL：
 * 1. 卫星数异常（0 或超过 [MAX_SATELLITES] 颗）、星座少于 [MIN_CONSTELLATIONS] 个；
 * 2. 相邻帧卫星集合（星座+svid）Jaccard 低于 [SHUFFLE_JACCARD]；
 * 3. CN0 为常数、全部 usedInFix=false；
 * 4. CN0 与仰角显著负相关/完全不相关（高仰角组不应低于低仰角组）。
 */
class GnssStatusCheck : Check {
    override val id = CheckIds.GNSS_STATUS
    override val title = "GnssStatus 卫星"

    override fun run(context: CheckContext): CheckResult {
        val snapshot = context.snapshot
        val frames = snapshot.gnssFrames
        if (frames.size < MIN_FRAMES) return insufficientFrames(snapshot, frames)

        val analysis = analyze(frames)
        val evidence = buildEvidence(snapshot, analysis)
        if (analysis.issues.isEmpty()) {
            return pass(
                buildString {
                    append("GnssStatus 正常：${frames.size} 帧、")
                    append("${analysis.knownConstellations.size} 个星座、")
                    append("卫星集合 Jaccard 最低 ${formatRatio(analysis.jaccardMin)}、")
                    append("CN0 ${formatDb(analysis.cn0Min)}–${formatDb(analysis.cn0Max)} dB-Hz、")
                    append("usedInFix ${analysis.usedInFixTotal}/${analysis.satelliteSamples}")
                },
                evidence,
            )
        }
        return fail(analysis.issues.joinToString("；"), evidence)
    }

    private fun insufficientFrames(
        snapshot: SampleSnapshot,
        frames: List<GnssFrameSnapshot>,
    ): CheckResult {
        val evidence = mutableListOf<String>()
        evidence += sampleWindowLine(snapshot)
        evidence += "GnssStatus 帧数=${frames.size}（至少需要 $MIN_FRAMES 帧）"
        if (frames.isNotEmpty()) {
            evidence += "已收到帧的卫星数：${sampleValues(frames.map { it.satellites.size.toString() })}"
        }
        evidence += "位置样本=${snapshot.locations.size}，NMEA 句数=${snapshot.nmeaSentences.size}"
        evidence += relevantNotes(snapshot)
        val reason = if (frames.isEmpty()) {
            "未收到 GnssStatus 回调（0 帧，少于 $MIN_FRAMES 帧）：可能缺少定位权限、GnssStatus 注册失败或采样窗口内无卫星状态更新"
        } else {
            "仅收到 ${frames.size} 帧 GnssStatus 回调（少于 $MIN_FRAMES 帧），无法判断卫星集合是否稳定：可能缺少定位权限或采样时间过短"
        }
        return unknown(reason, evidence.filter { it.isNotEmpty() }.map { it.take(MAX_EVIDENCE_CHARS) })
    }

    private fun analyze(frames: List<GnssFrameSnapshot>): GnssAnalysis {
        val issues = mutableListOf<String>()
        val satellitesPerFrame = frames.map { it.satellites.size }
        val constellationsPerFrame = frames.map { frame ->
            frame.satellites.map { it.constellation }.filter { it != GnssStatus.CONSTELLATION_UNKNOWN }.distinct().size
        }

        val abnormalFrames = frames.withIndex()
            .filter { (_, frame) -> frame.satellites.isEmpty() || frame.satellites.size > MAX_SATELLITES }
        if (abnormalFrames.isNotEmpty()) {
            val detail = abnormalFrames.take(4).joinToString("，") { (index, frame) ->
                "第 ${index + 1} 帧 ${frame.satellites.size} 颗"
            }
            val suffix = if (abnormalFrames.size > 4) " 等 ${abnormalFrames.size} 帧" else ""
            issues += "卫星数异常：$detail$suffix（正常每帧 1–$MAX_SATELLITES 颗）"
        }

        val constellationHistogram = linkedMapOf<Int, Int>()
        frames.forEach { frame ->
            frame.satellites.forEach { satellite ->
                constellationHistogram[satellite.constellation] =
                    (constellationHistogram[satellite.constellation] ?: 0) + 1
            }
        }
        val knownConstellations = constellationHistogram.keys
            .filter { it != GnssStatus.CONSTELLATION_UNKNOWN }
        if (knownConstellations.size < MIN_CONSTELLATIONS) {
            val names = if (knownConstellations.isEmpty()) {
                "无"
            } else {
                knownConstellations.joinToString("/") { constellationName(it) }
            }
            val beidouOnly = knownConstellations.size == 1 &&
                knownConstellations.first() == GnssStatus.CONSTELLATION_BEIDOU
            issues += "星座数 ${knownConstellations.size}（$names${if (beidouOnly) "，全部为 BEIDOU" else ""}），" +
                "少于 $MIN_CONSTELLATIONS 个"
        }

        val usedInFixPerFrame = frames.map { frame -> frame.satellites.count { it.usedInFix } }
        val usedInFixTotal = usedInFixPerFrame.sum()
        val satelliteSamples = satellitesPerFrame.sum()
        if (satelliteSamples > 0 && usedInFixTotal == 0) {
            issues += "全部 $satelliteSamples 个卫星样本 usedInFix=false"
        }

        val cn0Values = frames.flatMap { frame -> frame.satellites.map { it.cn0DbHz } }
            .filter { it.isFinite() }
        val cn0Positive = cn0Values.filter { it > 0f }
        val cn0Min = cn0Positive.minOrNull()
        val cn0Max = cn0Positive.maxOrNull()
        val cn0Distinct = cn0Values.distinct().size
        if (cn0Values.size >= 2 && cn0Distinct == 1) {
            issues += "所有卫星 CN0 完全相同（${formatDb(cn0Values.first())} dB-Hz）"
        } else if (cn0Positive.size >= MIN_CONSTANT_CN0_SAMPLES &&
            cn0Min != null && cn0Max != null && cn0Max - cn0Min <= CN0_CONSTANT_RANGE_DB
        ) {
            issues += "CN0 几乎为常数（${formatDb(cn0Min)}–${formatDb(cn0Max)} dB-Hz，" +
                "${cn0Positive.size} 个样本）"
        }

        val keySets = frames.map { frame ->
            frame.satellites.map { it.constellation to it.svid }.toSet()
        }
        val jaccardValues = (0 until frames.size - 1).mapNotNull { index ->
            val previous = keySets[index]
            val next = keySets[index + 1]
            if (previous.isEmpty() || next.isEmpty()) null else jaccard(previous, next)
        }
        val jaccardMin = jaccardValues.minOrNull()
        val jaccardAvg = if (jaccardValues.isEmpty()) null else jaccardValues.average()
        if (jaccardMin != null && jaccardMin < SHUFFLE_JACCARD) {
            issues += "相邻帧卫星集合 Jaccard 最低 ${formatRatio(jaccardMin)}" +
                "（< ${formatRatio(SHUFFLE_JACCARD)}），疑似每帧 shuffle"
        }

        val correlation = correlate(frames)
        correlation.verdict?.let { issues += it }

        return GnssAnalysis(
            frames = frames,
            satellitesPerFrame = satellitesPerFrame,
            constellationsPerFrame = constellationsPerFrame,
            knownConstellations = knownConstellations,
            constellationHistogram = constellationHistogram,
            usedInFixPerFrame = usedInFixPerFrame,
            usedInFixTotal = usedInFixTotal,
            satelliteSamples = satelliteSamples,
            cn0Min = cn0Min,
            cn0Max = cn0Max,
            cn0Distinct = cn0Distinct,
            cn0Samples = cn0Positive.size,
            jaccardMin = jaccardMin,
            jaccardAvg = jaccardAvg,
            jaccardPairs = jaccardValues.size,
            correlation = correlation,
            issues = issues,
        )
    }

    /**
     * 粗相关性：整体 Pearson r + 每帧“高仰角组 CN0 均值 − 低仰角组 CN0 均值”的中位数。
     * 样本不足时不判定（避免把稀疏星空误判成伪造）。
     */
    private fun correlate(frames: List<GnssFrameSnapshot>): CorrelationStats {
        val observations = frames.flatMap { frame ->
            frame.satellites.mapNotNull { satellite -> cn0ElevationPair(satellite) }
        }
        val minElevation = observations.minOfOrNull { it.first }
        val maxElevation = observations.maxOfOrNull { it.first }
        val elevationSpread = if (minElevation != null && maxElevation != null) {
            maxElevation - minElevation
        } else {
            0f
        }
        val pearson = if (observations.size >= MIN_CORRELATION_SAMPLES &&
            elevationSpread >= MIN_ELEVATION_SPREAD_DEG
        ) {
            pearsonCoefficient(observations)
        } else {
            null
        }

        val groupDeltas = frames.mapNotNull { frame ->
            val pairs = frame.satellites.mapNotNull { satellite -> cn0ElevationPair(satellite) }
                .sortedBy { it.first }
            if (pairs.size < MIN_GROUP_SATELLITES) return@mapNotNull null
            val half = pairs.size / 2
            val lowMean = pairs.take(half).map { it.second }.average()
            val highMean = pairs.takeLast(half).map { it.second }.average()
            (highMean - lowMean).toFloat()
        }
        val groupMedianDelta = median(groupDeltas)
        val verdict = if (pearson == null) {
            null
        } else if (pearson <= STRONG_NEGATIVE_CORRELATION) {
            "CN0 与仰角显著负相关（r=${formatCoefficient(pearson)}）：仰角越高 CN0 反而越低"
        } else if (groupMedianDelta != null && groupMedianDelta <= -GROUP_NOISE_DB &&
            pearson < GROUP_INVERSION_MAX_R
        ) {
            "高仰角组 CN0 比低仰角组低 ${formatDb(-groupMedianDelta)} dB" +
                "（${groupDeltas.size} 帧中位差，超过 ${formatDb(GROUP_NOISE_DB)} dB 噪声容限）"
        } else if (abs(pearson) < NO_CORRELATION_R) {
            "CN0 与仰角完全不相关（r=${formatCoefficient(pearson)}，${observations.size} 个样本）"
        } else {
            null
        }
        return CorrelationStats(
            samples = observations.size,
            elevationSpread = elevationSpread,
            pearson = pearson,
            groupFrames = groupDeltas.size,
            groupMedianDelta = groupMedianDelta,
            verdict = verdict,
        )
    }

    private fun buildEvidence(snapshot: SampleSnapshot, analysis: GnssAnalysis): List<String> {
        val evidence = mutableListOf<String>()
        evidence += sampleWindowLine(snapshot)
        evidence += "每帧卫星数：${sampleValues(analysis.satellitesPerFrame.map { it.toString() })}" +
            "（min=${analysis.satellitesPerFrame.minOrNull() ?: 0}，" +
            "max=${analysis.satellitesPerFrame.maxOrNull() ?: 0}）"
        evidence += "星座直方图：${constellationHistogramLine(analysis.constellationHistogram)}"
        evidence += "每帧星座数：${sampleValues(analysis.constellationsPerFrame.map { it.toString() })}"
        evidence += "usedInFix：每帧 ${sampleValues(analysis.usedInFixPerFrame.map { it.toString() })}；" +
            "合计 ${analysis.usedInFixTotal}/${analysis.satelliteSamples}"
        evidence += "CN0 dB-Hz：min=${formatDb(analysis.cn0Min)}，max=${formatDb(analysis.cn0Max)}，" +
            "不同值=${analysis.cn0Distinct}，有效样本=${analysis.cn0Samples}"
        evidence += "卫星集合 Jaccard：min=${formatRatio(analysis.jaccardMin)}，" +
            "avg=${formatRatio(analysis.jaccardAvg)}（相邻 ${analysis.jaccardPairs} 对）"
        evidence += "CN0/仰角：${correlationLine(analysis.correlation)}"
        evidence += relevantNotes(snapshot)
        return evidence.filter { it.isNotEmpty() }
            .take(MAX_EVIDENCE_LINES)
            .map { it.take(MAX_EVIDENCE_CHARS) }
    }

    private fun sampleWindowLine(snapshot: SampleSnapshot): String {
        val durationSeconds = ((snapshot.endedAtMillis - snapshot.startedAtMillis).coerceAtLeast(0L)) / 1000
        val start = formatTime(snapshot.startedAtMillis)
        val end = formatTime(snapshot.endedAtMillis)
        return "采样窗口 $start–$end（${durationSeconds}s），位置样本=${snapshot.locations.size}，" +
            "GnssStatus 帧数=${snapshot.gnssFrames.size}"
    }

    private fun relevantNotes(snapshot: SampleSnapshot): String {
        val notes = snapshot.notes.filter { note ->
            note.contains("gnss", ignoreCase = true) ||
                note.contains("nmea", ignoreCase = true) ||
                note.contains("locationmanager", ignoreCase = true)
        }
        if (notes.isEmpty()) return ""
        return "采集备注：${notes.take(3).joinToString("；") { it.take(120) }}"
    }

    private fun constellationHistogramLine(histogram: Map<Int, Int>): String {
        if (histogram.isEmpty()) return "无卫星"
        return histogram.entries.joinToString("，") { (constellation, count) ->
            "${constellationName(constellation)}=$count"
        }
    }

    private fun correlationLine(stats: CorrelationStats): String {
        if (stats.pearson == null) {
            return "样本不足（${stats.samples} 个有效样本，仰角跨度 ${formatDegrees(stats.elevationSpread)}°），跳过相关性判定"
        }
        val medianDelta = stats.groupMedianDelta
        val group = if (medianDelta != null) {
            "高仰角组−低仰角组中位差=${formatDb(medianDelta)} dB（${stats.groupFrames} 帧）"
        } else {
            "高/低仰角分组样本不足"
        }
        return "Pearson r=${formatCoefficient(stats.pearson)}，$group，${stats.samples} 个样本"
    }

    companion object {
        /** 少于 3 帧无法判断时间稳定性，按文档给 UNKNOWN。 */
        private const val MIN_FRAMES = 3

        /** GnssStatus 正常不会单帧超过 64 颗；超过按伪造处理。 */
        private const val MAX_SATELLITES = 64

        private const val MIN_CONSTELLATIONS = 2

        /** 相邻帧卫星集合 Jaccard 低于该值视为 shuffle。 */
        private const val SHUFFLE_JACCARD = 0.5

        /** CN0 有效值少于该数时不判定“常数”。 */
        private const val MIN_CONSTANT_CN0_SAMPLES = 4

        /** 所有正 CN0 的极差不超过该值视为常数。 */
        private const val CN0_CONSTANT_RANGE_DB = 0.1f

        private const val MIN_CORRELATION_SAMPLES = 16
        private const val MIN_ELEVATION_SPREAD_DEG = 10f
        private const val MIN_GROUP_SATELLITES = 6
        private const val STRONG_NEGATIVE_CORRELATION = -0.3f
        private const val NO_CORRELATION_R = 0.05f
        private const val GROUP_NOISE_DB = 1.5f
        private const val GROUP_INVERSION_MAX_R = 0.15f

        private const val MAX_EVIDENCE_LINES = 20
        private const val MAX_EVIDENCE_CHARS = 299
    }
}

private data class CorrelationStats(
    val samples: Int,
    val elevationSpread: Float,
    val pearson: Float?,
    val groupFrames: Int,
    val groupMedianDelta: Float?,
    val verdict: String?,
)

private data class GnssAnalysis(
    val frames: List<GnssFrameSnapshot>,
    val satellitesPerFrame: List<Int>,
    val constellationsPerFrame: List<Int>,
    val knownConstellations: List<Int>,
    val constellationHistogram: Map<Int, Int>,
    val usedInFixPerFrame: List<Int>,
    val usedInFixTotal: Int,
    val satelliteSamples: Int,
    val cn0Min: Float?,
    val cn0Max: Float?,
    val cn0Distinct: Int,
    val cn0Samples: Int,
    val jaccardMin: Double?,
    val jaccardAvg: Double?,
    val jaccardPairs: Int,
    val correlation: CorrelationStats,
    val issues: List<String>,
)

private fun constellationName(constellation: Int): String = when (constellation) {
    GnssStatus.CONSTELLATION_GPS -> "GPS"
    GnssStatus.CONSTELLATION_SBAS -> "SBAS"
    GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
    GnssStatus.CONSTELLATION_QZSS -> "QZSS"
    GnssStatus.CONSTELLATION_BEIDOU -> "BEIDOU"
    GnssStatus.CONSTELLATION_GALILEO -> "GALILEO"
    GnssStatus.CONSTELLATION_IRNSS -> "IRNSS"
    GnssStatus.CONSTELLATION_UNKNOWN -> "UNKNOWN"
    else -> "TYPE_$constellation"
}

private fun cn0ElevationPair(satellite: GnssSatelliteSnapshot): Pair<Float, Float>? {
    val elevation = satellite.elevationDegrees
    val cn0 = satellite.cn0DbHz
    if (!elevation.isFinite() || elevation < 0f || elevation > 90f) return null
    if (!cn0.isFinite() || cn0 <= 0f) return null
    return elevation to cn0
}

private fun pearsonCoefficient(pairs: List<Pair<Float, Float>>): Float? {
    if (pairs.size < 2) return null
    val meanX = pairs.map { it.first.toDouble() }.average()
    val meanY = pairs.map { it.second.toDouble() }.average()
    var sumXY = 0.0
    var sumXX = 0.0
    var sumYY = 0.0
    pairs.forEach { (x, y) ->
        val dx = x.toDouble() - meanX
        val dy = y.toDouble() - meanY
        sumXY += dx * dy
        sumXX += dx * dx
        sumYY += dy * dy
    }
    if (sumXX <= 1e-9 || sumYY <= 1e-9) return null
    return (sumXY / sqrt(sumXX * sumYY)).toFloat()
}

private fun jaccard(previous: Set<Pair<Int, Int>>, next: Set<Pair<Int, Int>>): Double {
    if (previous.isEmpty() && next.isEmpty()) return 1.0
    val intersection = previous.count { it in next }
    val union = previous.size + next.size - intersection
    return if (union == 0) 1.0 else intersection.toDouble() / union
}

private fun median(values: List<Float>): Float? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) {
        sorted[middle]
    } else {
        (sorted[middle - 1] + sorted[middle]) / 2f
    }
}

private fun formatTime(millis: Long): String {
    if (millis <= 0L) return "--:--:--"
    return SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(millis))
}

private fun formatDb(value: Float?): String =
    value?.let { String.format(Locale.US, "%.1f", it) } ?: "n/a"

private fun formatCoefficient(value: Float?): String =
    value?.let { String.format(Locale.US, "%.2f", it) } ?: "n/a"

private fun formatRatio(value: Double?): String =
    value?.let { String.format(Locale.US, "%.2f", it) } ?: "n/a"

private fun formatDegrees(value: Float): String = String.format(Locale.US, "%.1f", value)

private fun sampleValues(values: List<String>, limit: Int = 12): String {
    if (values.isEmpty()) return "无"
    if (values.size <= limit) return values.joinToString(", ")
    return values.take(limit).joinToString(", ") + " …（共 ${values.size} 帧）"
}
