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
 * 期望：GGA/RMC/GSA/GSV 校验和正确；GGA 在用数不超过同时间窗 GSV 可见数；
 * talker ID 与星座匹配，且定位状态（GGA quality / RMC status）与位置样本不矛盾。
 *
 * 解析规则：`$` + talker+type + 逗号分隔字段 + `*HH`，按 NMEA 0183 XOR 校验和验证。
 * 完全没有 NMEA 时返回 UNKNOWN（未启用或未获得输出），
 * 语句类型不足时 UNKNOWN；校验和错误、无法解析或交叉校验矛盾时 FAIL。
 */
class NmeaCheck : Check {
    override val id = CheckIds.NMEA
    override val title = "NMEA 语句"

    override fun run(context: CheckContext): CheckResult = evaluate(context.snapshot)

    internal fun evaluate(snapshot: SampleSnapshot): CheckResult {
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
            val missing = REQUIRED_TYPES.filter { analysis.typeCounts[it] == 0 }
            if (missing.isNotEmpty()) return unknown(
                "未收到 ${missing.joinToString("/")}，现有语句未见硬性矛盾，完整自洽性证据不足", evidence,
            )
            if (analysis.comparisons.isEmpty()) return unknown(
                "已收到语句，但没有覆盖完整星座的同时间窗 GGA/GSV 配对，无法完成数量对账", evidence,
            )
            if (analysis.gsvVsGnssDiff?.let { it > 0 } == true || analysis.sourceConcerns.isNotEmpty()) return unknown(
                "NMEA 与 GnssStatus 存在同时间窗差异，需核对信号覆盖和回调时序", evidence + analysis.sourceConcerns,
            )
            return pass(
                "NMEA 自洽：可解析 ${summary.parsed.size}/${rawSentences.size} 句，GGA/RMC/GSA/GSV 已收到，" +
                    "校验和正确，可比较的在用数/可见数未见矛盾，talker 与星座匹配",
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
        val gstList = byType["GST"].orEmpty()
        val zdaList = byType["ZDA"].orEmpty()

        val issues = mutableListOf<String>()
        val sourceConcerns = mutableListOf<String>()
        if (summary.checksumFailures.isNotEmpty()) {
            issues += "NMEA 校验和错误 ${summary.checksumFailures.size} 条"
        }
        if (summary.unparsable.isNotEmpty()) {
            issues += "NMEA 无法解析 ${summary.unparsable.size} 条"
        }

        val ggaSatellites = ggaList.mapNotNull { sentence ->
            sentence.fields.getOrNull(6)?.trim()?.toIntOrNull()
        }
        val ggaQualities = ggaList.mapNotNull { sentence ->
            sentence.fields.getOrNull(5)?.trim()?.toIntOrNull()
        }
        val latestGgaQuality = ggaList.lastOrNull()?.fields?.getOrNull(5)?.trim()?.toIntOrNull()
        val latestRmcStatus = rmcList.lastOrNull()?.fields?.getOrNull(1)?.trim()?.uppercase(Locale.US)

        val latestGst = gstList.lastOrNull()
        if (latestGst != null) {
            val utc = latestGst.fields.getOrNull(0)?.trim()
            val sameInstantGga = ggaList.lastOrNull { it.timestampMillis == latestGst.timestampMillis }
            val ggaUtc = sameInstantGga?.fields?.getOrNull(0)?.toDoubleOrNull()
            if (ggaUtc != null && utc?.toDoubleOrNull() != ggaUtc) {
                issues += "GST 与同刻 GGA UTC 不一致"
            }
            val errors = latestGst.fields.drop(5).mapNotNull { it.trim().toDoubleOrNull() }
            if (errors.size < 3 || errors.any { it < 0.0 || !it.isFinite() }) {
                issues += "GST 误差字段缺失或为负"
            }
        }
        val latestZda = zdaList.lastOrNull()
        if (latestZda != null) {
            val utc = latestZda.fields.getOrNull(0)?.trim()
            val sameInstantGga = ggaList.lastOrNull { it.timestampMillis == latestZda.timestampMillis }
            val ggaUtc = sameInstantGga?.fields?.getOrNull(0)?.toDoubleOrNull()
            if (ggaUtc != null && utc?.toDoubleOrNull() != ggaUtc) {
                issues += "ZDA 与同刻 GGA UTC 不一致"
            }
            val day = latestZda.fields.getOrNull(1)?.trim()?.toIntOrNull()
            val month = latestZda.fields.getOrNull(2)?.trim()?.toIntOrNull()
            val year = latestZda.fields.getOrNull(3)?.trim()?.toIntOrNull()
            if (day !in 1..31 || month !in 1..12 || year == null || year < 2000) {
                issues += "ZDA 日期字段非法（$day/$month/$year）"
            }
        }

        ggaList.forEach { gga ->
            val used = gga.fields.getOrNull(6)?.toIntOrNull()
            val quality = gga.fields.getOrNull(5)?.toIntOrNull()
            if (used == null || used < 0 || quality == null || quality !in 0..8)
                issues += "GGA 卫星数或定位质量字段非法"
            if (quality == 0 && used != null && used > 0) issues += "GGA 无解却声明 $used 颗卫星参与定位"
            // Network/cached/mock Location can coexist with no current GNSS fix.
            val gsa = gsaList.filter { it.timestampMillis == gga.timestampMillis }
            if (quality != null && quality > 0 && gsa.any { it.fields.getOrNull(1) == "3" } && used != null && used < 4)
                issues += "GSA 声明三维定位，但同刻 GGA 仅 $used 颗卫星在用"
        }
        gsaList.forEach { gsa ->
            val fix = gsa.fields.getOrNull(1)?.toIntOrNull()
            val dop = gsa.fields.drop(14).take(3)
            if (fix !in 1..3) issues += "GSA 定位类型非法"
            if (fix in 2..3 && (dop.size != 3 || dop.any { it.toDoubleOrNull()?.let { value -> value.isFinite() && value > 0 } != true }))
                issues += "GSA 有解但 DOP 缺失或无效"
        }

        val bursts = groupGsvBursts(parsed)
        val validation = validateGsvBursts(bursts)
        issues += validation.issues
        val burstTotals = bursts.mapNotNull { it.satelliteTotal() }

        gsaList.forEach { gsa ->
            val slots = gsa.fields.drop(2).take(12).filter(String::isNotBlank)
            if (slots.isEmpty()) return@forEach
            val systemTalker = if (gsa.talker == "GN") SYSTEM_ID_TALKERS[gsa.fields.getOrNull(17)?.toIntOrNull()]
                else gsa.talker.takeIf { it in TALKER_PRN_RANGES }
            if (systemTalker == null) {
                sourceConcerns += "GSA 卫星编号缺少可确定的星座信息，未猜测 GN 混合编号"
                return@forEach
            }
            val ranges = TALKER_PRN_RANGES.getValue(systemTalker)
            val numbers = slots.map { it.toIntOrNull() }
            if (numbers.any { number -> number == null || ranges.none { number in it } }) {
                issues += "GSA 在用卫星编号与星座不匹配或不是整数"
                return@forEach
            }
            val identities = numbers.mapNotNull { gsvIdentity(systemTalker, it!!) }
            if (identities.distinct().size != identities.size) issues += "单条 GSA 重复声明同一在用卫星"
            val burst = nearestBurst(bursts, gsa)
            if (burst != null) {
                val visible = burst.sentences.flatMap { sentence -> gsvSatelliteGroups(sentence).mapNotNull { group ->
                    group.firstOrNull()?.toIntOrNull()?.let { gsvIdentity(sentence.talker, it) }
                } }.toSet()
                val missing = identities.filter { it !in visible }
                if (missing.isNotEmpty()) sourceConcerns += "GSA 在用身份未见于同时间窗完整 GSV：${missing.take(3)}"
            }
            val status = snapshot.gnssFrames.minByOrNull { abs(it.capturedAtMillis - gsa.timestampMillis) }
                ?.takeIf { gsa.timestampMillis > 0 && abs(it.capturedAtMillis - gsa.timestampMillis) <= 1000 }
            if (status != null) {
                val used = status.satellites.filter { it.usedInFix }.mapNotNull { satellite ->
                    STATUS_SYSTEM_NAMES[satellite.constellation]?.let { it to satellite.svid }
                }.toSet()
                if (identities.any { it !in used }) sourceConcerns += "GSA 在用身份与同时间窗 GnssStatus 的 usedInFix 不一致"
            }
        }

        val comparisons = ggaList.mapNotNull { gga ->
            val ggaCount = gga.fields.getOrNull(6)?.trim()?.toIntOrNull() ?: return@mapNotNull null
            val burst = nearestBurst(bursts, gga) ?: return@mapNotNull null
            val gsvTotal = burst.satelliteTotal() ?: return@mapNotNull null
            if (gga.talker == "GN" && ggaCount > 0) {
                val usedSystems = gsaList.filter {
                    abs(it.timestampMillis - gga.timestampMillis) <= 1000 &&
                        it.fields.drop(2).take(12).any(String::isNotBlank)
                }.map { it.fields.getOrNull(17)?.toIntOrNull() ?: TALKER_SYSTEM_IDS[it.talker] }
                val represented = burst.sentences.mapNotNull { TALKER_SYSTEM_IDS[it.talker] }.toSet()
                if (usedSystems.isEmpty() || usedSystems.any { it == null || it !in represented })
                    return@mapNotNull null
            } else if (gga.talker != "GN" && burst.sentences.any { it.talker != gga.talker }) {
                // System-specific GGA cannot be compared with a combined total.
                return@mapNotNull null
            }
            GgaGsvPair(ggaCount, gsvTotal, ggaCount - gsvTotal)
        }
        val maxGgaGsvDiff = comparisons.maxOfOrNull { it.diff }
        if (maxGgaGsvDiff != null && maxGgaGsvDiff > 0) {
            issues += "同时间窗 GGA 在用数超过 GSV 可见数（最多 $maxGgaGsvDiff 颗）"
        }

        val latestGsvTotal = bursts.lastOrNull { it.isComplete() }?.satelliteTotal()
            ?: bursts.lastOrNull()?.satelliteTotal()
        var gsvVsGnssDiff: Int? = null
        val latestBurst = bursts.lastOrNull { it.isComplete() }
        val matchedFrame = latestBurst?.let { burst -> snapshot.gnssFrames.minByOrNull {
            abs(it.capturedAtMillis - burst.anchorMillis)
        }?.takeIf { abs(it.capturedAtMillis - burst.anchorMillis) <= 1000L } }
        var matchedFrameCount: Int? = null
        if (matchedFrame != null) {
            // A talker may report only some constellations/signals. Compare only represented systems.
            val represented = latestBurst.sentences.flatMap { TALKER_CONSTELLATIONS[it.talker].orEmpty() }.toSet()
            val statusCount = matchedFrame.satellites.filter { it.constellation in represented }
                .map { it.constellation to it.svid }.distinct().size
            matchedFrameCount = statusCount
            val reported = latestBurst.satelliteTotal()
            gsvVsGnssDiff = reported?.let { abs(statusCount - it) }
        }

        // 只校验真正报告了卫星的 talker：部分设备会为当前不可见星座输出 0 星 GSV。
        val gsvTalkers = latestBurst?.sentences.orEmpty()
            .filter { sentence -> gsvSatelliteCount(sentence) > 0 }
            .map { it.talker }
            .distinct()
        if (matchedFrame != null && matchedFrame.satellites.isNotEmpty()) {
            val frameConstellations = matchedFrame.satellites.map { it.constellation }.toSet()
            val unmatched = gsvTalkers.filter { talker ->
                TALKER_CONSTELLATIONS[talker]?.let { systems -> systems.none { it in frameConstellations } } == true
            }
            if (unmatched.isNotEmpty()) {
                sourceConcerns += "GSV talker ${unmatched.joinToString("/")} 对应星座不在同时间窗 GnssStatus 中"
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
            latestFrameCount = matchedFrameCount,
            gsvVsGnssDiff = gsvVsGnssDiff,
            issues = issues,
            sourceConcerns = sourceConcerns,
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
            "${analysis.latestGsvTotal ?: "n/a"}，配对 GnssStatus 对应星座唯一卫星=${analysis.latestFrameCount ?: "n/a"}" +
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
    val timestampMillis: Long,
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
    val sourceConcerns: List<String>,
)

/** 同一接收时刻内 $xxGSV 的连续块；按卫星编号是否重置划分。 */
private class GsvBurst(
    val index: Int,
    val anchorMillis: Long,
    val firstSequence: Int,
    val sentences: MutableList<ParsedNmea> = mutableListOf(),
    val bySignal: MutableMap<String, MutableList<ParsedNmea>> = linkedMapOf(),
) {
    /** Count unique spacecraft across signals; GN-only numbering is not safely resolvable. */
    fun satelliteTotal(): Int? {
        val identities = sentences.flatMap { sentence ->
            gsvSatelliteGroups(sentence).mapNotNull { group ->
                val prn = group.firstOrNull()?.toIntOrNull() ?: return@mapNotNull null
                gsvIdentity(sentence.talker, prn) ?: return null
            }
        }
        return identities.toSet().size
    }

    fun isComplete(): Boolean {
        if (bySignal.isEmpty()) return false
        bySignal.forEach { (_, talkerSentences) ->
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
    "GP" to setOf(GnssStatus.CONSTELLATION_GPS, GnssStatus.CONSTELLATION_SBAS),
    "BD" to setOf(GnssStatus.CONSTELLATION_BEIDOU),
    "GB" to setOf(GnssStatus.CONSTELLATION_BEIDOU),
    "GL" to setOf(GnssStatus.CONSTELLATION_GLONASS),
    "GA" to setOf(GnssStatus.CONSTELLATION_GALILEO),
    "GQ" to setOf(GnssStatus.CONSTELLATION_QZSS),
    "GI" to setOf(GnssStatus.CONSTELLATION_IRNSS),
)

/** NMEA 4.1 GSA systemId：1=GPS、2=GLONASS、3=Galileo、4=BeiDou、5=QZSS、6=NavIC。 */
private val TALKER_SYSTEM_IDS = mapOf(
    "GP" to 1,
    "GL" to 2,
    "GA" to 3,
    "BD" to 4,
    "GQ" to 5,
    "GB" to 4,
    "GI" to 6,
)

private val SYSTEM_ID_TALKERS = mapOf(1 to "GP", 2 to "GL", 3 to "GA", 4 to "GB", 5 to "GQ", 6 to "GI")
private val STATUS_SYSTEM_NAMES = mapOf(
    GnssStatus.CONSTELLATION_GPS to "GPS", GnssStatus.CONSTELLATION_SBAS to "SBAS",
    GnssStatus.CONSTELLATION_GLONASS to "GLO", GnssStatus.CONSTELLATION_GALILEO to "GAL",
    GnssStatus.CONSTELLATION_BEIDOU to "BDS", GnssStatus.CONSTELLATION_QZSS to "QZSS",
    GnssStatus.CONSTELLATION_IRNSS to "IRNSS",
)

/**
 * 各 talker 合理的 PRN/SVID 范围（含常见厂商变体与 SBAS/QZSS 混排）。
 * 用于发现“把 BDS/Galileo 卫星塞进 GPGSV”这类明显错配，低编号区间允许重叠。
 */
private val TALKER_PRN_RANGES = mapOf(
    "GP" to listOf(1..64, 120..158, 193..200),
    "BD" to listOf(1..64, 201..235),
    "GL" to listOf(1..25, 65..96),
    "GA" to listOf(1..36, 301..336),
    "GB" to listOf(1..64, 201..235),
    "GQ" to listOf(1..10, 193..202),
    "GI" to listOf(1..14),
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
    if (checksumText.length != 2) return ParseOutcome.Unparsable(line)
    val expected = checksumText.toIntOrNull(16) ?: return ParseOutcome.ChecksumFailure(line)
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
            timestampMillis = snapshot.timestampMillis,
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
                val signalKey = gsvSignalKey(sentence)
                val previousNumber = current?.bySignal?.get(signalKey)
                    ?.lastOrNull()?.fields?.getOrNull(1)?.trim()?.toIntOrNull()
                val restart = previousNumber != null &&
                    (number == 1 || (number != null && number <= previousNumber))
                val startNew = current == null || epochMarkerSeen || restart ||
                    abs(sentence.timestampMillis - current.anchorMillis) > 1000L
                val burst = if (startNew) {
                    GsvBurst(bursts.size, sentence.timestampMillis, sentence.sequence).also {
                        bursts += it
                        current = it
                    }
                } else {
                    checkNotNull(current)
                }
                if (startNew) epochMarkerSeen = false
                burst.sentences += sentence
                burst.bySignal.getOrPut(signalKey) { mutableListOf() } += sentence
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
        val validHeaders = burst.sentences.all {
            val messages = it.fields.getOrNull(0)?.toIntOrNull()
            val number = it.fields.getOrNull(1)?.toIntOrNull()
            messages != null && messages > 0 && number != null && number in 1..messages &&
                (it.fields.getOrNull(2)?.toIntOrNull() ?: -1) >= 0
        }
        if (validHeaders && !burst.isComplete() && (index == 0 || index == bursts.lastIndex)) {
            boundaryBursts++
            return@forEachIndexed
        }
        burst.bySignal.forEach { (talker, talkerSentences) ->
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
    if (gga.timestampMillis <= 0L) return null
    return bursts.filter { it.isComplete() && it.anchorMillis > 0 && abs(it.anchorMillis - gga.timestampMillis) <= 1000L }
        .minByOrNull { abs(it.anchorMillis - gga.timestampMillis) }

}

/** Optional NMEA 4.x signal ID belongs to a separate message sequence. */
private fun gsvSignalKey(sentence: ParsedNmea): String {
    val payload = sentence.fields.drop(3)
    val signal = if (payload.size % 4 == 1) payload.last() else ""
    return "${sentence.talker}:$signal"
}

private fun gsvIdentity(talker: String, prn: Int): Pair<String, Int>? = when (talker) {
    "GP" -> when (prn) {
        in 1..32 -> "GPS" to prn
        in 33..64 -> "SBAS" to prn + 87
        in 120..158 -> "SBAS" to prn
        in 193..202 -> "QZSS" to prn
        else -> null
    }
    "BD", "GB" -> "BDS" to if (prn >= 201) prn - 200 else prn
    "GA" -> "GAL" to if (prn >= 301) prn - 300 else prn
    "GL" -> "GLO" to if (prn >= 65) prn - 64 else prn
    "GQ" -> "QZSS" to if (prn < 193) prn + 192 else prn
    "GI" -> "IRNSS" to prn
    else -> null // Mixed GN numbering depends on receiver version; don't invent a mapping.
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
