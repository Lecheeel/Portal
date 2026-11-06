package com.system.location.service.detector.checks

import android.location.GnssStatus
import com.system.location.service.detector.model.Check
import com.system.location.service.detector.model.CheckContext
import com.system.location.service.detector.model.CheckIds
import com.system.location.service.detector.model.CheckResult
import com.system.location.service.detector.model.NmeaSentenceSnapshot
import com.system.location.service.detector.model.SampleSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * C07 · NMEA 自洽性。
 *
 * 期望：GGA/RMC/GSA/GSV 校验和正确；GSV 卫星数与 GGA 卫星数、GnssStatus 一致；
 * talker ID 与星座匹配，且定位状态（GGA quality / RMC status）与位置样本不矛盾。
 *
 * 解析规则：`$` + talker+type + 逗号分隔字段 + `*HH`，按 NMEA 0183 XOR 校验和验证。
 * 无法解析的句子只记录、不参与判定；完全没有 NMEA 时返回 UNKNOWN（未启用），
 * 收到语句但缺必需类型/校验和错误/交叉校验矛盾时 FAIL。
 */
class NmeaCheck : Check {
    override val id = CheckIds.NMEA
    override val title = "NMEA 语句"

    override fun run(context: CheckContext): CheckResult {
        val snapshot = context.snapshot
        val rawSentences = snapshot.nmeaSentences
        if (rawSentences.isEmpty()) {
            return unknown(
                "未收到任何 NMEA 语句：设备可能未启用 NMEA 输出、缺少定位权限或 NMEA 监听注册失败",
                emptyEvidence(snapshot),
            )
        }

        val summary = parseAll(rawSentences)
        val analysis = analyze(snapshot, summary)
        val evidence = buildEvidence(snapshot, analysis)
        if (analysis.issues.isEmpty()) {
            return pass(
                "NMEA 自洽：可解析 ${summary.parsed.size}/${rawSentences.size} 句，GGA/RMC/GSA/GSV 齐全，" +
                    "校验和全部正确，GGA/GSV/GnssStatus 卫星数一致，talker 与星座匹配",
                evidence,
            )
        }
        return fail(analysis.issues.joinToString("；"), evidence)
    }

    private fun analyze(snapshot: SampleSnapshot, summary: ParseSummary): NmeaAnalysis {
        val parsed = summary.parsed
        val byType = parsed.groupBy { it.type }
        val typeCounts = TYPE_ORDER.associateWith { type -> byType[type]?.size ?: 0 }
        val talkerCounts = parsed.groupingBy { it.talker }.eachCount().toSortedMap()
        val ggaList = byType["GGA"].orEmpty()
        val rmcList = byType["RMC"].orEmpty()
        val gsaList = byType["GSA"].orEmpty()
        val gsvList = byType["GSV"].orEmpty()

        val issues = mutableListOf<String>()
        if (summary.checksumFailures.isNotEmpty()) {
            issues += "NMEA 校验和错误 ${summary.checksumFailures.size} 条"
        }
        val missing = REQUIRED_TYPES.filter { type -> (typeCounts[type] ?: 0) == 0 }
        if (missing.isNotEmpty()) {
            issues += "缺少必需语句 ${missing.joinToString("/")}"
        }

        val ggaSatellites = ggaList.mapNotNull { sentence ->
            sentence.fields.getOrNull(6)?.trim()?.toIntOrNull()
        }
        val ggaQualities = ggaList.mapNotNull { sentence ->
            sentence.fields.getOrNull(5)?.trim()?.toIntOrNull()
        }
        val latestGgaQuality = ggaList.lastOrNull()?.fields?.getOrNull(5)?.trim()?.toIntOrNull()
        val latestRmcStatus = rmcList.lastOrNull()?.fields?.getOrNull(1)?.trim()?.uppercase(Locale.US)
        val hasLocations = snapshot.locations.isNotEmpty()

        if (hasLocations && latestGgaQuality == 0) {
            issues += "最新 GGA fixQuality=0 但已收到 ${snapshot.locations.size} 个位置样本"
        }
        if (hasLocations && latestRmcStatus == "V") {
            issues += "最新 RMC status=V 但已收到 ${snapshot.locations.size} 个位置样本"
        }

        val bursts = groupGsvBursts(parsed)
        val validation = validateGsvBursts(bursts)
        issues += validation.issues
        val burstTotals = bursts.mapNotNull { it.satelliteTotal() }

        val comparisons = ggaList.mapNotNull { gga ->
            val ggaCount = gga.fields.getOrNull(6)?.trim()?.toIntOrNull() ?: return@mapNotNull null
            val burst = nearestBurst(bursts, gga) ?: return@mapNotNull null
            val gsvTotal = burst.satelliteTotal() ?: return@mapNotNull null
            GgaGsvPair(ggaCount, gsvTotal, abs(ggaCount - gsvTotal))
        }
        val maxGgaGsvDiff = comparisons.maxOfOrNull { it.diff }
        if (maxGgaGsvDiff != null && maxGgaGsvDiff > MAX_GGA_GSV_DIFF) {
            issues += "GGA 卫星数与最近 GSV 星座卫星合计最大相差 $maxGgaGsvDiff（> $MAX_GGA_GSV_DIFF）"
        }

        val latestFrame = snapshot.gnssFrames.maxByOrNull { it.capturedAtMillis }
        val latestFrameCount = latestFrame?.satellites?.size
        val latestGsvTotal = bursts.lastOrNull { it.isComplete() }?.satelliteTotal()
            ?: bursts.lastOrNull()?.satelliteTotal()
        var gsvVsGnssDiff: Int? = null
        if (latestFrameCount != null && latestGsvTotal != null) {
            val diff = abs(latestFrameCount - latestGsvTotal)
            gsvVsGnssDiff = diff
            if (diff > MAX_GSV_GNSS_DIFF) {
                issues += "GSV 卫星合计 $latestGsvTotal 与最新 GnssStatus $latestFrameCount 相差 " +
                    "$diff（> $MAX_GSV_GNSS_DIFF）"
            }
        }

        // 只校验真正报告了卫星的 talker：部分设备会为当前不可见星座输出 0 星 GSV。
        val gsvTalkers = gsvList
            .filter { sentence -> gsvSatelliteCount(sentence) > 0 }
            .map { it.talker }
            .distinct()
        if (latestFrame != null && latestFrame.satellites.isNotEmpty()) {
            val frameConstellations = latestFrame.satellites.map { it.constellation }.toSet()
            val unmatched = gsvTalkers.filter { talker ->
                TALKER_CONSTELLATIONS[talker]?.let { it !in frameConstellations } == true
            }
            if (unmatched.isNotEmpty()) {
                issues += "GSV talker ${unmatched.joinToString("/")} 对应星座不在最新 GnssStatus 中"
            }
        }

        val gsaMismatches = gsaList.mapNotNull { gsa ->
            val systemId = gsa.fields.getOrNull(17)?.trim()?.toIntOrNull() ?: return@mapNotNull null
            val expected = TALKER_SYSTEM_IDS[gsa.talker] ?: return@mapNotNull null
            if (systemId == expected) null else "${gsa.talker}GSA systemId=$systemId（应为 $expected）"
        }
        if (gsaMismatches.isNotEmpty()) {
            issues += "talker 与 GSA systemId 不匹配：${gsaMismatches.take(2).joinToString("，")}"
        }

        val prnMismatches = mutableListOf<String>()
        gsvList.forEach { sentence ->
            val ranges = TALKER_PRN_RANGES[sentence.talker] ?: return@forEach
            val invalid = gsvSatelliteGroups(sentence)
                .mapNotNull { group -> group.firstOrNull()?.trim()?.toIntOrNull() }
                .filter { prn -> ranges.none { prn in it } }
            if (invalid.isNotEmpty()) {
                prnMismatches += "${sentence.talker}GSV 含 PRN ${invalid.take(3).joinToString("/")}"
            }
        }
        if (prnMismatches.isNotEmpty()) {
            issues += "talker 与卫星 PRN 不匹配：${prnMismatches.take(2).joinToString("，")}"
        }

        return NmeaAnalysis(
            rawCount = rawSentencesCount(snapshot),
            parsedCount = parsed.size,
            checksumFailures = summary.checksumFailures,
            unparsable = summary.unparsable,
            typeCounts = typeCounts,
            talkerCounts = talkerCounts,
            ggaSatellites = ggaSatellites,
            ggaQualities = ggaQualities,
            latestGgaQuality = latestGgaQuality,
            latestRmcStatus = latestRmcStatus,
            firstGgaUtc = ggaList.firstOrNull()?.fields?.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() },
            lastGgaUtc = ggaList.lastOrNull()?.fields?.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() },
            latestRmcDateTime = rmcList.lastOrNull()?.let { rmc ->
                val time = rmc.fields.getOrNull(0)?.trim().orEmpty()
                val date = rmc.fields.getOrNull(8)?.trim().orEmpty()
                listOf(date, time).filter { it.isNotEmpty() }.joinToString(" ").takeIf { it.isNotEmpty() }
            },
            bursts = bursts,
            boundaryBursts = validation.boundaryBursts,
            burstTotals = burstTotals,
            comparisons = comparisons,
            maxGgaGsvDiff = maxGgaGsvDiff,
            latestGsvTotal = latestGsvTotal,
            latestFrameCount = latestFrameCount,
            gsvVsGnssDiff = gsvVsGnssDiff,
            issues = issues,
        )
    }

    private fun rawSentencesCount(snapshot: SampleSnapshot): Int = snapshot.nmeaSentences.size

    private fun buildEvidence(snapshot: SampleSnapshot, analysis: NmeaAnalysis): List<String> {
        val evidence = mutableListOf<String>()
        val durationSeconds = ((snapshot.endedAtMillis - snapshot.startedAtMillis).coerceAtLeast(0L)) / 1000
        evidence += "采样窗口 ${formatTime(snapshot.startedAtMillis)}–${formatTime(snapshot.endedAtMillis)}" +
            "（${durationSeconds}s）；原始 ${analysis.rawCount} 句，可解析 ${analysis.parsedCount}，校验和失败 " +
            "${analysis.checksumFailures.size}，无法解析 ${analysis.unparsable.size}"
        evidence += "句数：" + TYPE_ORDER.joinToString(" ") { type -> "$type=${analysis.typeCounts[type] ?: 0}" }
        evidence += "talker：" + if (analysis.talkerCounts.isEmpty()) {
            "无"
        } else {
            analysis.talkerCounts.entries.take(12).joinToString("，") { "${it.key}=${it.value}" }
        }
        if (analysis.checksumFailures.isNotEmpty()) {
            evidence += "校验和失败样例：${analysis.checksumFailures.take(2).joinToString("；") { it.take(100) }}"
        }
        if (analysis.unparsable.isNotEmpty()) {
            evidence += "无法解析样例：${analysis.unparsable.take(2).joinToString("；") { it.take(100) }}"
        }
        evidence += "GGA 卫星数：" + if (analysis.ggaSatellites.isEmpty()) {
            "无"
        } else {
            "${analysis.ggaSatellites.minOrNull()}–${analysis.ggaSatellites.maxOrNull()}" +
                "（最新 ${analysis.ggaSatellites.last()}）"
        }
        evidence += "GGA fixQuality：${histogram(analysis.ggaQualities)}；最新 GGA quality=" +
            "${analysis.latestGgaQuality ?: "n/a"}，最新 RMC status=${analysis.latestRmcStatus ?: "n/a"}，" +
            "位置样本=${snapshot.locations.size}"
        evidence += "GGA↔GSV：" + if (analysis.comparisons.isEmpty()) {
            "无可配对"
        } else {
            val latest = analysis.comparisons.last()
            "${analysis.comparisons.size} 组，最大差 ${analysis.maxGgaGsvDiff}；最新 GGA=${latest.ggaCount}" +
                " vs 最近 GSV 合计=${latest.gsvTotal}"
        }
        evidence += "GSV：${analysis.bursts.size} 组 burst" +
            (if (analysis.boundaryBursts > 0) "（边界截断 ${analysis.boundaryBursts} 组已忽略）" else "") +
            "；各组合计 ${sampleValues(analysis.burstTotals.map { it.toString() })}；最新合计=" +
            "${analysis.latestGsvTotal ?: "n/a"}，最新 GnssStatus=${analysis.latestFrameCount ?: "n/a"}" +
            (analysis.gsvVsGnssDiff?.let { "，差=$it" } ?: "")
        evidence += "NMEA UTC：GGA ${analysis.firstGgaUtc ?: "无"} → ${analysis.lastGgaUtc ?: "无"}；" +
            "RMC 最新 ${analysis.latestRmcDateTime ?: "无"}"
        return evidence.filter { it.isNotEmpty() }
            .take(MAX_EVIDENCE_LINES)
            .map { it.take(MAX_EVIDENCE_CHARS) }
    }

    private fun emptyEvidence(snapshot: SampleSnapshot): List<String> {
        val evidence = mutableListOf<String>()
        val durationSeconds = ((snapshot.endedAtMillis - snapshot.startedAtMillis).coerceAtLeast(0L)) / 1000
        evidence += "采样窗口 ${formatTime(snapshot.startedAtMillis)}–${formatTime(snapshot.endedAtMillis)}（${durationSeconds}s）"
        evidence += "NMEA 句数=0，位置样本=${snapshot.locations.size}，GnssStatus 帧数=${snapshot.gnssFrames.size}"
        val notes = snapshot.notes.filter { note ->
            note.contains("nmea", ignoreCase = true) || note.contains("gnss", ignoreCase = true)
        }
        if (notes.isNotEmpty()) {
            evidence += "采集备注：${notes.take(3).joinToString("；") { it.take(120) }}"
        }
        return evidence.map { it.take(MAX_EVIDENCE_CHARS) }
    }

    companion object {
        private const val MAX_GGA_GSV_DIFF = 1
        private const val MAX_GSV_GNSS_DIFF = 2
        private const val MAX_EVIDENCE_LINES = 20
        private const val MAX_EVIDENCE_CHARS = 299
    }
}

private sealed interface ParseOutcome {
    data class Parsed(val sentence: ParsedNmea) : ParseOutcome
    data class ChecksumFailure(val raw: String) : ParseOutcome
    data class Unparsable(val raw: String) : ParseOutcome
}

private data class ParsedNmea(
    val sequence: Int,
    val capturedAtNanos: Long,
    val talker: String,
    val type: String,
    val fields: List<String>,
    val raw: String,
)

private data class ParseSummary(
    val parsed: List<ParsedNmea>,
    val checksumFailures: List<String>,
    val unparsable: List<String>,
)

private data class GgaGsvPair(
    val ggaCount: Int,
    val gsvTotal: Int,
    val diff: Int,
)

private data class GsvValidation(
    val issues: List<String>,
    val boundaryBursts: Int,
)

private data class NmeaAnalysis(
    val rawCount: Int,
    val parsedCount: Int,
    val checksumFailures: List<String>,
    val unparsable: List<String>,
    val typeCounts: Map<String, Int>,
    val talkerCounts: Map<String, Int>,
    val ggaSatellites: List<Int>,
    val ggaQualities: List<Int>,
    val latestGgaQuality: Int?,
    val latestRmcStatus: String?,
    val firstGgaUtc: String?,
    val lastGgaUtc: String?,
    val latestRmcDateTime: String?,
    val bursts: List<GsvBurst>,
    val boundaryBursts: Int,
    val burstTotals: List<Int>,
    val comparisons: List<GgaGsvPair>,
    val maxGgaGsvDiff: Int?,
    val latestGsvTotal: Int?,
    val latestFrameCount: Int?,
    val gsvVsGnssDiff: Int?,
    val issues: List<String>,
)

/** 同一接收时刻内 $xxGSV 的连续块；按卫星编号是否重置划分。 */
private class GsvBurst(
    val index: Int,
    val anchorNanos: Long,
    val firstSequence: Int,
    val sentences: MutableList<ParsedNmea> = mutableListOf(),
    val byTalker: MutableMap<String, MutableList<ParsedNmea>> = linkedMapOf(),
) {
    /** 各星座 GSV 头 totalSatellitesInView 之和；只有 GN 组合 talker 时退化为该值。 */
    fun satelliteTotal(): Int? {
        var constellationTotal = 0
        var constellationTalkers = 0
        var combinedTotal: Int? = null
        byTalker.forEach { (talker, talkerSentences) ->
            val declared = talkerSentences.firstOrNull()?.fields?.getOrNull(2)?.trim()?.toIntOrNull()
                ?: return@forEach
            if (talker == "GN") {
                combinedTotal = declared
            } else {
                constellationTotal += declared
                constellationTalkers++
            }
        }
        return if (constellationTalkers > 0) constellationTotal else combinedTotal
    }

    fun isComplete(): Boolean {
        if (byTalker.isEmpty()) return false
        byTalker.forEach { (_, talkerSentences) ->
            val header = talkerSentences.firstOrNull() ?: return false
            val totalMessages = header.fields.getOrNull(0)?.trim()?.toIntOrNull() ?: return false
            val declaredTotal = header.fields.getOrNull(2)?.trim()?.toIntOrNull() ?: return false
            if (totalMessages <= 0 || declaredTotal < 0) return false
            val numbers = talkerSentences.mapNotNull { sentence ->
                sentence.fields.getOrNull(1)?.trim()?.toIntOrNull()
            }
            if (numbers.size != totalMessages) return false
            if (numbers.toSet() != (1..totalMessages).toSet()) return false
            if (talkerSentences.sumOf { gsvSatelliteCount(it) } != declaredTotal) return false
        }
        return true
    }
}

private val TYPE_ORDER = listOf("GGA", "RMC", "GSA", "GSV", "GST", "ZDA")
private val REQUIRED_TYPES = listOf("GGA", "RMC", "GSA", "GSV")

private val TALKER_CONSTELLATIONS = mapOf(
    "GP" to GnssStatus.CONSTELLATION_GPS,
    "BD" to GnssStatus.CONSTELLATION_BEIDOU,
    "GL" to GnssStatus.CONSTELLATION_GLONASS,
    "GA" to GnssStatus.CONSTELLATION_GALILEO,
)

/** NMEA 4.1 GSA systemId：1=GPS、2=GLONASS、3=Galileo、4=BeiDou、5=QZSS、6=NavIC。 */
private val TALKER_SYSTEM_IDS = mapOf(
    "GP" to 1,
    "GL" to 2,
    "GA" to 3,
    "BD" to 4,
    "QZ" to 5,
    "GI" to 6,
)

/**
 * 各 talker 合理的 PRN/SVID 范围（含常见厂商变体与 SBAS/QZSS 混排）。
 * 用于发现“把 BDS/Galileo 卫星塞进 GPGSV”这类明显错配，低编号区间允许重叠。
 */
private val TALKER_PRN_RANGES = mapOf(
    "GP" to listOf(1..64, 120..158, 193..200),
    "BD" to listOf(1..64, 201..235),
    "GL" to listOf(1..24, 65..96),
    "GA" to listOf(1..36, 301..336),
)

private fun parseAll(sentences: List<NmeaSentenceSnapshot>): ParseSummary {
    val parsed = mutableListOf<ParsedNmea>()
    val checksumFailures = mutableListOf<String>()
    val unparsable = mutableListOf<String>()
    sentences.forEachIndexed { index, sentence ->
        when (val outcome = parseSentence(sentence, index)) {
            is ParseOutcome.Parsed -> parsed += outcome.sentence
            is ParseOutcome.ChecksumFailure -> checksumFailures += outcome.raw
            is ParseOutcome.Unparsable -> unparsable += outcome.raw
        }
    }
    return ParseSummary(parsed, checksumFailures, unparsable)
}

private fun parseSentence(snapshot: NmeaSentenceSnapshot, sequence: Int): ParseOutcome {
    val line = snapshot.sentence.trim()
    if (line.isEmpty()) return ParseOutcome.Unparsable(snapshot.sentence)
    if (!line.startsWith('$')) return ParseOutcome.Unparsable(line)
    val body = line.substring(1)
    val star = body.lastIndexOf('*')
    if (star < 0) return ParseOutcome.Unparsable(line)
    val payload = body.substring(0, star)
    val checksumText = body.substring(star + 1).trim()
    if (checksumText.length < 2) return ParseOutcome.Unparsable(line)
    val expected = checksumText.substring(0, 2).toIntOrNull(16) ?: return ParseOutcome.Unparsable(line)
    val actual = payload.fold(0) { checksum, char -> checksum xor char.code }
    if (actual != expected) return ParseOutcome.ChecksumFailure(line)
    val parts = payload.split(',')
    val sentenceId = parts.firstOrNull()?.trim()?.uppercase(Locale.US).orEmpty()
    if (sentenceId.length < 5 || sentenceId.any { it !in 'A'..'Z' }) {
        return ParseOutcome.Unparsable(line)
    }
    return ParseOutcome.Parsed(
        ParsedNmea(
            sequence = sequence,
            capturedAtNanos = snapshot.capturedAtNanos,
            talker = sentenceId.substring(0, 2),
            type = sentenceId.substring(2),
            fields = parts.drop(1),
            raw = line,
        ),
    )
}

private fun groupGsvBursts(sentences: List<ParsedNmea>): List<GsvBurst> {
    val bursts = mutableListOf<GsvBurst>()
    var current: GsvBurst? = null
    var epochMarkerSeen = false
    sentences.forEach { sentence ->
        when (sentence.type) {
            "GGA", "RMC" -> epochMarkerSeen = true
            "GSV" -> {
                val number = sentence.fields.getOrNull(1)?.trim()?.toIntOrNull()
                val previousNumber = current?.byTalker?.get(sentence.talker)
                    ?.lastOrNull()?.fields?.getOrNull(1)?.trim()?.toIntOrNull()
                val restart = previousNumber != null &&
                    (number == 1 || (number != null && number <= previousNumber))
                val startNew = current == null || epochMarkerSeen || restart
                val burst = if (startNew) {
                    GsvBurst(bursts.size, sentence.capturedAtNanos, sentence.sequence).also {
                        bursts += it
                        current = it
                    }
                } else {
                    checkNotNull(current)
                }
                if (startNew) epochMarkerSeen = false
                burst.sentences += sentence
                burst.byTalker.getOrPut(sentence.talker) { mutableListOf() } += sentence
            }
            else -> Unit
        }
    }
    return bursts
}

private fun validateGsvBursts(bursts: List<GsvBurst>): GsvValidation {
    val issues = mutableListOf<String>()
    var headerMismatches = 0
    var numberMismatches = 0
    var satelliteMismatches = 0
    var boundaryBursts = 0
    val samples = mutableListOf<String>()
    bursts.forEachIndexed { index, burst ->
        // 采样开始/结束时可能截断一整组 GSV，不当作生成器错误。
        if (!burst.isComplete() && (index == 0 || index == bursts.lastIndex)) {
            boundaryBursts++
            return@forEachIndexed
        }
        burst.byTalker.forEach { (talker, talkerSentences) ->
            val header = talkerSentences.firstOrNull() ?: return@forEach
            val totalMessages = header.fields.getOrNull(0)?.trim()?.toIntOrNull()
            val declaredTotal = header.fields.getOrNull(2)?.trim()?.toIntOrNull()
            if (totalMessages == null || totalMessages <= 0 || declaredTotal == null || declaredTotal < 0) {
                headerMismatches++
                if (samples.size < 2) samples += "第 ${header.sequence + 1} 句 GSV 头字段非法"
                return@forEach
            }
            val declarationsConsistent = talkerSentences.all { sentence ->
                sentence.fields.getOrNull(0)?.trim()?.toIntOrNull() == totalMessages &&
                    sentence.fields.getOrNull(2)?.trim()?.toIntOrNull() == declaredTotal
            }
            val numbers = talkerSentences.mapNotNull { sentence ->
                sentence.fields.getOrNull(1)?.trim()?.toIntOrNull()
            }
            val numbersOk = numbers.size == totalMessages &&
                numbers.toSet() == (1..totalMessages).toSet()
            val counts = talkerSentences.map { sentence -> gsvSatelliteCount(sentence) }
            val countsOk = counts.withIndex().all { (sentenceIndex, count) ->
                when {
                    declaredTotal == 0 -> count == 0
                    sentenceIndex == counts.lastIndex -> count in 1..4
                    else -> count == 4
                }
            }
            val sumOk = counts.sum() == declaredTotal
            if (!declarationsConsistent || !numbersOk || !countsOk || !sumOk) {
                if (!declarationsConsistent) headerMismatches++
                if (!numbersOk) numberMismatches++
                if (!countsOk || !sumOk) satelliteMismatches++
                if (samples.size < 2) {
                    samples += "$talker 第 ${burst.index + 1} 组：句号 ${numbers.joinToString("/")}" +
                        "（应为 1..$totalMessages），卫星 ${counts.joinToString("+")}（头总计 $declaredTotal）"
                }
            }
        }
    }
    if (headerMismatches > 0) issues += "GSV 头字段不一致或不合法（$headerMismatches 组）"
    if (numberMismatches > 0) issues += "GSV 句数与头 totalMessages 不一致（$numberMismatches 组）"
    if (satelliteMismatches > 0) {
        val sample = if (samples.isEmpty()) "" else "，如 ${samples.first()}"
        issues += "GSV 每句卫星数与头 totalSatellitesInView 不一致（$satelliteMismatches 组$sample）"
    }
    return GsvValidation(issues, boundaryBursts)
}

private fun nearestBurst(bursts: List<GsvBurst>, gga: ParsedNmea): GsvBurst? {
    if (bursts.isEmpty()) return null
    if (gga.capturedAtNanos == 0L || bursts.all { it.anchorNanos == 0L }) {
        return bursts.minByOrNull { burst -> abs(burst.firstSequence.toLong() - gga.sequence.toLong()) }
    }
    return bursts.minByOrNull { burst -> abs(burst.anchorNanos - gga.capturedAtNanos) }
}

private fun gsvSatelliteGroups(sentence: ParsedNmea): List<List<String>> {
    val payload = sentence.fields.drop(3)
    if (payload.isEmpty()) return emptyList()
    return payload.dropLast(payload.size % 4).chunked(4)
}

private fun gsvSatelliteCount(sentence: ParsedNmea): Int {
    val groups = gsvSatelliteGroups(sentence)
    if (groups.isEmpty()) return 0
    return groups.count { group -> group.firstOrNull()?.trim()?.isNotEmpty() == true }
}

private fun histogram(values: List<Int>): String {
    if (values.isEmpty()) return "无"
    return values.groupingBy { it }.eachCount().toSortedMap()
        .entries.joinToString("，") { "${it.key}=${it.value}" }
}

private fun formatTime(millis: Long): String {
    if (millis <= 0L) return "--:--:--"
    return SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(millis))
}

private fun sampleValues(values: List<String>, limit: Int = 12): String {
    if (values.isEmpty()) return "无"
    if (values.size <= limit) return values.joinToString(", ")
    return values.take(limit).joinToString(", ") + " …（共 ${values.size} 组）"
}
