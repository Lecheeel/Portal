package com.system.location.service.detector.checks

import android.location.Location
import android.location.LocationManager
import com.system.location.service.detector.model.Check
import com.system.location.service.detector.model.CheckContext
import com.system.location.service.detector.model.CheckIds
import com.system.location.service.detector.model.CheckResult
import java.util.Locale
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * C12 · 轨迹统计。
 *
 * 期望：无瞬移（速度突变超限）、时间戳单调、无固定周期抖动（DFT 单频峰）。
 *
 * 判定口径：
 * - 多 provider 时只分析样本最多的一个（GPS 优先），避免多源交叉时间戳误判。
 * - 时间戳倒退、重复时间戳但坐标变化、单步 >5 km、瞬时速度 >200 m/s → FAIL。
 * - 少于 20 点或短于 30 s → UNKNOWN；路线图建议连续采样 2–5 分钟。
 * - 对去趋势后的局部坐标做 DFT（最多 256 点），k>=3（至少 3 个周期）且非 DC 主频能量
 *   占比 >0.6、振幅 >0.5 m → FAIL。k<3 视为转弯/绕圈趋势，不算固定抖动。
 */
class TrajectoryCheck : Check {
    override val id = CheckIds.TRAJECTORY
    override val title = "轨迹统计"

    override fun run(context: CheckContext): CheckResult = try {
        val allLocations = context.snapshot.locations
        val evidence = mutableListOf<String>()
        if (allLocations.isEmpty()) {
            return unknown("无定位样本，无法分析轨迹。", listOf("locations=0"))
        }

        val groups = allLocations.groupBy { it.provider ?: "unknown" }
        val selected = groups.entries.maxWithOrNull(
            compareBy<Map.Entry<String, List<Location>>> { it.value.size }
                .thenBy { if (it.key == LocationManager.GPS_PROVIDER) 1 else 0 },
        ) ?: return unknown("无法按 provider 分组轨迹。", evidence)
        val provider = selected.key
        val locations = selected.value
        val otherProviders = groups.filterKeys { it != provider }
            .entries.joinToString(",") { "${it.key}=${it.value.size}" }
            .ifBlank { "无" }
        addTrajectoryEvidence(
            evidence,
            "provider=$provider，样本=${locations.size}/${allLocations.size}；其他 provider：$otherProviders（不混合分析）",
        )

        val anomalies = mutableListOf<String>()
        var maxSpeed = 0.0
        var pathDistance = 0.0
        var validSeconds = 0.0
        var sameTimeSamePoint = 0
        for (index in 1 until locations.size) {
            val previous = locations[index - 1]
            val current = locations[index]
            val dt = (current.time - previous.time) / 1000.0
            val distance = trajectoryHaversineMeters(previous, current)
            when {
                dt < 0.0 -> anomalies += "时间戳倒退 ${previous.time}→${current.time}（#${index}）"
                dt == 0.0 -> {
                    if (distance > 0.05) {
                        anomalies += "重复时间戳但坐标变化 ${formatTrajectoryDouble(distance)} m（#${index}）"
                    } else {
                        sameTimeSamePoint++
                    }
                }
                else -> {
                    if (distance > TRAJECTORY_MAX_STEP_METERS) {
                        anomalies += "单步位移 ${formatTrajectoryDouble(distance / 1000.0)} km > 5 km（#${index}）"
                    }
                    val speed = distance / dt
                    if (speed > TRAJECTORY_MAX_SPEED_MPS) {
                        anomalies += "瞬时速度 ${formatTrajectoryDouble(speed)} m/s > 200（#${index}）"
                    }
                    if (speed > maxSpeed) maxSpeed = speed
                    pathDistance += distance
                    validSeconds += dt
                }
            }
        }

        val durationSeconds = (locations.last().time - locations.first().time) / 1000.0
        val averageSpeed = if (validSeconds > 0.0) pathDistance / validSeconds else 0.0
        addTrajectoryEvidence(
            evidence,
            "样本=${locations.size} 点，时长=${formatTrajectoryDouble(durationSeconds)} s，" +
                "最大速度=${formatTrajectoryDouble(maxSpeed)} m/s，平均速度=${formatTrajectoryDouble(averageSpeed)} m/s",
        )
        addTrajectoryEvidence(
            evidence,
            "时间戳异常=${anomalies.size} 条，重复时间戳同坐标=$sameTimeSamePoint 条",
        )
        anomalies.take(5).forEach { addTrajectoryEvidence(evidence, "  异常：$it") }
        if (anomalies.size > 5) addTrajectoryEvidence(evidence, "  ……其余 ${anomalies.size - 5} 条从略")

        if (anomalies.isNotEmpty()) {
            return fail("轨迹存在 ${anomalies.size} 项时间戳/瞬移硬异常，详见证据。", evidence)
        }

        if (locations.size < TRAJECTORY_MIN_POINTS || durationSeconds < TRAJECTORY_MIN_SECONDS) {
            return unknown(
                "轨迹样本不足（${locations.size} 点 / ${formatTrajectoryDouble(durationSeconds)} s）：" +
                    "至少 20 点且 30 秒才能判定；路线图建议连续采样 2–5 分钟。",
                evidence,
            )
        }

        val distinct = locations.filterIndexed { index, location ->
            index == 0 || location.time > locations[index - 1].time
        }
        val spectra = analyzePeriodicity(distinct)
        val jitter = spectra.firstOrNull { it.ratio > TRAJECTORY_MAX_FREQUENCY_RATIO && it.amplitude > 0.5 }
        val shown = jitter ?: spectra.maxByOrNull { it.ratio }
        if (shown == null) {
            addTrajectoryEvidence(evidence, "频域：可用样本不足或残差能量过低，跳过 DFT")
        } else {
            addTrajectoryEvidence(
                evidence,
                "频域（最多 256 点，${shown.points} 点）：轴=${shown.axis} k=${shown.bin} " +
                    "周期=${formatTrajectoryDouble(shown.periodSeconds)} s " +
                    "能量占比=${formatTrajectoryDouble(shown.ratio)} " +
                    "振幅=${formatTrajectoryDouble(shown.amplitude)} m",
            )
            if (jitter != null) {
                return fail(
                    "轨迹疑似固定周期抖动：周期 ${formatTrajectoryDouble(shown.periodSeconds)} s，" +
                        "主频能量占比 ${formatTrajectoryDouble(shown.ratio)}（>0.6），" +
                        "振幅 ${formatTrajectoryDouble(shown.amplitude)} m（>0.5）。",
                    evidence,
                )
            }
        }

        return pass(
            "轨迹正常：${locations.size} 点 / ${formatTrajectoryDouble(durationSeconds)} s，" +
                "最大速度 ${formatTrajectoryDouble(maxSpeed)} m/s（<=200），" +
                "平均速度 ${formatTrajectoryDouble(averageSpeed)} m/s，时间戳异常 0，无固定周期抖动。",
            evidence,
        )
    } catch (error: Throwable) {
        unknown(
            "C12 检查异常：${error.javaClass.simpleName}: ${error.message}",
            listOf("异常：${error.javaClass.simpleName}"),
        )
    }
}

private data class SpectrumCandidate(
    val axis: String,
    val bin: Int,
    val periodSeconds: Double,
    val ratio: Double,
    val amplitude: Double,
    val points: Int,
)

/** Returns one candidate per axis; null when the residual has too little usable energy. */
private fun analyzePeriodicity(locations: List<Location>): List<SpectrumCandidate> {
    if (locations.size < TRAJECTORY_MIN_DFT_POINTS) return emptyList()
    val step = maxOf(1, (locations.size + TRAJECTORY_MAX_DFT_POINTS - 1) / TRAJECTORY_MAX_DFT_POINTS)
    val sampled = locations.filterIndexed { index, _ -> index % step == 0 }
        .take(TRAJECTORY_MAX_DFT_POINTS)
    if (sampled.size < TRAJECTORY_MIN_DFT_POINTS) return emptyList()
    val spanSeconds = (sampled.last().time - sampled.first().time) / 1000.0
    if (spanSeconds <= 0.0) return emptyList()
    val meanDt = spanSeconds / (sampled.size - 1)
    val origin = sampled.first()
    val metersPerDegreeLat = 111_320.0
    val metersPerDegreeLon = 111_320.0 * cos(Math.toRadians(origin.latitude))
    val xs = sampled.map { (it.longitude - origin.longitude) * metersPerDegreeLon }
    val ys = sampled.map { (it.latitude - origin.latitude) * metersPerDegreeLat }
    return listOfNotNull(
        spectrumCandidate(xs, "lon/x", meanDt),
        spectrumCandidate(ys, "lat/y", meanDt),
    )
}

private fun spectrumCandidate(
    values: List<Double>,
    axis: String,
    meanDt: Double,
): SpectrumCandidate? {
    val residual = detrend(values)
    val n = residual.size
    if (n < TRAJECTORY_MIN_DFT_POINTS) return null
    val bins = n / 2
    if (bins < 3) return null
    val power = DoubleArray(bins + 1)
    var total = 0.0
    for (bin in 1..bins) {
        var real = 0.0
        var imaginary = 0.0
        val angleStep = 2.0 * PI * bin / n
        for (index in 0 until n) {
            val angle = angleStep * index
            real += residual[index] * cos(angle)
            imaginary -= residual[index] * sin(angle)
        }
        val value = real * real + imaginary * imaginary
        power[bin] = value
        total += value
    }
    if (total <= 1e-12) return null
    var best = 1
    for (bin in 2..bins) if (power[bin] > power[best]) best = bin
    // 少于 3 个周期更像单次转弯/绕圈趋势，不作为“固定周期抖动”。
    if (best < 3) return null
    val ratio = power[best] / total
    val amplitude = 2.0 * sqrt(power[best]) / n
    return SpectrumCandidate(
        axis = axis,
        bin = best,
        periodSeconds = n * meanDt / best,
        ratio = ratio,
        amplitude = amplitude,
        points = n,
    )
}

private fun detrend(values: List<Double>): DoubleArray {
    val n = values.size
    if (n < 2) return DoubleArray(0)
    val meanIndex = (n - 1) / 2.0
    val meanValue = values.average()
    var numerator = 0.0
    var denominator = 0.0
    for (index in 0 until n) {
        val deltaIndex = index - meanIndex
        numerator += deltaIndex * (values[index] - meanValue)
        denominator += deltaIndex * deltaIndex
    }
    val slope = if (denominator > 0.0) numerator / denominator else 0.0
    val intercept = meanValue - slope * meanIndex
    return DoubleArray(n) { index -> values[index] - (intercept + slope * index) }
}

private fun trajectoryHaversineMeters(from: Location, to: Location): Double {
    val lat1 = Math.toRadians(from.latitude)
    val lat2 = Math.toRadians(to.latitude)
    val deltaLat = lat2 - lat1
    val deltaLon = Math.toRadians(to.longitude - from.longitude)
    val h = sin(deltaLat / 2) * sin(deltaLat / 2) +
        cos(lat1) * cos(lat2) * sin(deltaLon / 2) * sin(deltaLon / 2)
    return 2 * 6_371_000.0 * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

private fun addTrajectoryEvidence(evidence: MutableList<String>, line: String) {
    if (evidence.size < TRAJECTORY_MAX_EVIDENCE_LINES) {
        evidence += line.take(TRAJECTORY_MAX_EVIDENCE_CHARS)
    }
}

private fun formatTrajectoryDouble(value: Double): String =
    if (!value.isFinite()) value.toString() else String.format(Locale.US, "%.2f", value)

private const val TRAJECTORY_MIN_POINTS = 20
private const val TRAJECTORY_MIN_SECONDS = 30.0
private const val TRAJECTORY_MAX_SPEED_MPS = 200.0
private const val TRAJECTORY_MAX_STEP_METERS = 5000.0
private const val TRAJECTORY_MAX_DFT_POINTS = 256
private const val TRAJECTORY_MIN_DFT_POINTS = 32
private const val TRAJECTORY_MAX_FREQUENCY_RATIO = 0.6
private const val TRAJECTORY_MAX_EVIDENCE_LINES = 20
private const val TRAJECTORY_MAX_EVIDENCE_CHARS = 299
