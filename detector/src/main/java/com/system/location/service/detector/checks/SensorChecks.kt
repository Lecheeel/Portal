package com.system.location.service.detector.checks

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import com.system.location.service.detector.model.Check
import com.system.location.service.detector.model.CheckContext
import com.system.location.service.detector.model.CheckIds
import com.system.location.service.detector.model.CheckResult
import com.system.location.service.detector.model.SampleSnapshot
import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * C10 · 传感器数值。
 *
 * 期望：加速度/陀螺仪/地磁/气压/计步都有真实量级和变化，不是全 0/-1 常量；
 * 静止时不应持续随机抖动，运动时与轨迹一致。
 *
 * 判定口径：
 * - 连续型传感器（加速度/陀螺仪/磁力/气压）有硬件但 0 样本 → FAIL；1–2 样本无法算方差 → UNKNOWN。
 * - 加速度、陀螺仪、磁力三轴方差为 0 → FAIL（当前 native 会写常量）。
 * - 加速度模长均值必须在 6.8–12.8 m/s²，气压 300–1100 hPa，磁力 10–100 µT。
 * - 计步非负且单调不减；STEP_DETECTOR 是事件型传感器，静止时 0 事件属正常。
 * - 无任何传感器数据 → UNKNOWN；没有 ACTIVITY_RECOGNITION 权限时计步项只记证据，不判 FAIL。
 */
class SensorCheck : Check {
    override val id = CheckIds.SENSOR
    override val title = "传感器数值"

    override fun run(context: CheckContext): CheckResult = try {
        val snapshot = context.snapshot
        val series = snapshot.sensorSeries
        val sensorManager = context.context.getSystemService(SensorManager::class.java)
        val activityPermission = runCatching {
            context.context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) ==
                PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        val evidence = mutableListOf<String>()
        val seconds = ((snapshot.endedAtMillis - snapshot.startedAtMillis).coerceAtLeast(0L)) / 1000.0
        addSensorEvidence(
            evidence,
            "采样 ${formatSensorDouble(seconds)} s；传感器 ${series.size} 类，样本总数 ${series.values.sumOf { it.values.size }}",
        )
        addSensorEvidence(
            evidence,
            "ACTIVITY_RECOGNITION=${if (activityPermission) "已授予" else "未授予/未声明（计步硬件可能不可见或注册失败）"}",
        )

        if (series.isEmpty()) {
            return unknown("采样窗口内没有任何传感器数据：可能无相关硬件、未授权或注册失败，无法判定。", evidence)
        }
        if (sensorManager == null) {
            return unknown("SensorManager 不可用，无法取得硬件清单；仅有 ${series.size} 类样本，不能判断量级。", evidence)
        }

        val failures = mutableListOf<String>()
        val incomplete = mutableListOf<String>()
        var checkedTypes = 0
        for (spec in SENSOR_SPECS) {
            val hardware = runCatching { sensorManager.getDefaultSensor(spec.type) != null }.getOrDefault(false)
            val samples = series[spec.type]?.values.orEmpty()
            if (hardware || samples.isNotEmpty()) checkedTypes++
            val finding = inspectSensor(spec, hardware, samples, activityPermission, evidence)
            if (finding != null) {
                if (finding.fatal) failures += finding.message else incomplete += finding.message
            }
        }

        when {
            failures.isNotEmpty() -> fail(
                "传感器数值异常：${failures.joinToString("；").take(240)}",
                evidence,
            )
            incomplete.isNotEmpty() -> unknown(
                "传感器数据不足，无法判定：${incomplete.joinToString("；").take(240)}",
                evidence,
            )
            checkedTypes == 0 -> unknown("未发现加速度/陀螺仪/磁力/气压/计步硬件或数据。", evidence)
            else -> pass(
                "已检查 $checkedTypes 类传感器：量级、方差与计步单调性正常；" +
                    "STEP_DETECTOR 无事件时按静止处理，不判 FAIL。",
                evidence,
            )
        }
    } catch (error: Throwable) {
        unknown(
            "C10 检查异常：${error.javaClass.simpleName}: ${error.message}",
            listOf("异常：${error.javaClass.simpleName}"),
        )
    }
}

/**
 * C11 · 活动识别 / 计步一致性。
 *
 * 判定口径：
 * - STEP_COUNTER 含 NaN/Inf、负值或负跳变 → FAIL。
 * - STEP_DETECTOR 有事件但 STEP_COUNTER 不增长 → FAIL。
 * - 采样 >=20 s 后：位移速度 >0.5 m/s 而计步不增长 → FAIL；速度 <0.3 m/s 而计步增长 → FAIL。
 * - 步态自洽 → PASS，并在 detail 说明 GMS Activity Recognition 未接入（P7 再做）。
 * - 无计步硬件/数据、或无法估算速度 → UNKNOWN。
 */
class ActivityRecognitionCheck : Check {
    override val id = CheckIds.ACTIVITY
    override val title = "活动识别与计步"

    override fun run(context: CheckContext): CheckResult = try {
        val snapshot = context.snapshot
        val sensorManager = context.context.getSystemService(SensorManager::class.java)
        val counterSamples = snapshot.sensorSeries[Sensor.TYPE_STEP_COUNTER]?.values.orEmpty()
        val detectorSamples = snapshot.sensorSeries[Sensor.TYPE_STEP_DETECTOR]?.values.orEmpty()
        val counterHardware = runCatching {
            sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null
        }.getOrDefault(false)
        val detectorHardware = runCatching {
            sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null
        }.getOrDefault(false)
        val activityPermission = runCatching {
            context.context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) ==
                PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        val durationSeconds = sampleDurationSeconds(snapshot)

        val evidence = mutableListOf<String>()
        addActivityEvidence(evidence, "采样时长 ${formatSensorDouble(durationSeconds)} s（判定要求 >=20 s）")
        addActivityEvidence(
            evidence,
            "STEP_COUNTER：硬件=$counterHardware，样本=${counterSamples.size}；" +
                "STEP_DETECTOR：硬件=$detectorHardware，事件=${detectorSamples.size}",
        )
        addActivityEvidence(
            evidence,
            "ACTIVITY_RECOGNITION=${if (activityPermission) "已授予" else "未授予/未声明"}；" +
                "未接入 GMS Activity Recognition（P7 再做），不因无法读取而判 FAIL",
        )

        if (counterSamples.isEmpty()) {
            return unknown(
                "无 STEP_COUNTER 硬件/数据" +
                    (if (!activityPermission) "（ACTIVITY_RECOGNITION 未授予/未声明，计步传感器可能未注册）" else "") +
                    "，无法判断计步与运动状态是否矛盾；Activity Recognition 未接入。",
                evidence,
            )
        }

        val counts = counterSamples.map { it.firstOrNull()?.toDouble() ?: Double.NaN }
        if (counts.any { !it.isFinite() }) {
            return fail("STEP_COUNTER 含 NaN/Inf（当前 native 可能把值写成 -1），计步数据不可信。", evidence)
        }
        if (counts.any { it < 0.0 }) {
            return fail("STEP_COUNTER 出现负值，计步数据不可信。", evidence)
        }

        var negativeJumps = 0
        for (index in 1 until counts.size) {
            if (counts[index] < counts[index - 1]) negativeJumps++
        }
        val delta = counts.last() - counts.first()
        addActivityEvidence(
            evidence,
            "计步首=${formatSensorDouble(counts.first())} 末=${formatSensorDouble(counts.last())} " +
                "增量=${formatSensorDouble(delta)} 负跳变=$negativeJumps",
        )
        if (negativeJumps > 0) {
            return fail("STEP_COUNTER 出现 $negativeJumps 次负跳变，计步不单调。", evidence)
        }

        val detectorEvents = detectorSamples.size
        if (detectorEvents > 0 && delta <= 0.0) {
            return fail(
                "STEP_DETECTOR 有 $detectorEvents 个事件，但 STEP_COUNTER 增量为 ${formatSensorDouble(delta)}，两者矛盾。",
                evidence,
            )
        }
        if (durationSeconds < ACTIVITY_MIN_SECONDS) {
            return unknown(
                "采样仅 ${formatSensorDouble(durationSeconds)} s，少于 20 s，无法可靠判断静止/运动与计步是否矛盾。",
                evidence,
            )
        }

        val speedLocations = selectSpeedLocations(snapshot)
        if (speedLocations.size < 2) {
            return unknown("无足够定位样本，无法估算速度并与计步比较；计步本身单调。", evidence)
        }
        val ordered = speedLocations.sortedBy { it.time }
        val spanSeconds = (ordered.last().time - ordered.first().time) / 1000.0
        if (spanSeconds < ACTIVITY_MIN_SPEED_SECONDS) {
            return unknown(
                "定位时长仅 ${formatSensorDouble(spanSeconds)} s，无法可靠估算速度（要求 >=10 s）。",
                evidence,
            )
        }

        val netDistance = sensorHaversineMeters(ordered.first(), ordered.last())
        val netSpeed = netDistance / spanSeconds
        var pathDistance = 0.0
        for (index in 1 until ordered.size) {
            val dt = (ordered[index].time - ordered[index - 1].time) / 1000.0
            if (dt > 0.0) pathDistance += sensorHaversineMeters(ordered[index - 1], ordered[index])
        }
        val pathSpeed = pathDistance / spanSeconds
        addActivityEvidence(
            evidence,
            "速度估算（${ordered.first().provider ?: "unknown"}）：位移=${formatSensorDouble(netDistance)} m，" +
                "位移速度=${formatSensorDouble(netSpeed)} m/s，路径平均=${formatSensorDouble(pathSpeed)} m/s，" +
                "定位时长=${formatSensorDouble(spanSeconds)} s",
        )

        if (netSpeed > ACTIVITY_MOVING_SPEED_MPS && delta <= 0.0) {
            return fail(
                "定位位移速度 ${formatSensorDouble(netSpeed)} m/s（>0.5），但 STEP_COUNTER 在采样窗口内没有增长。",
                evidence,
            )
        }
        if (netSpeed < ACTIVITY_STILL_SPEED_MPS && delta > 0.0) {
            return fail(
                "定位几乎静止（位移速度 ${formatSensorDouble(netSpeed)} m/s），但 STEP_COUNTER 增长 " +
                    "${formatSensorDouble(delta)} 步。",
                evidence,
            )
        }

        val ambiguous = netSpeed in ACTIVITY_STILL_SPEED_MPS..ACTIVITY_MOVING_SPEED_MPS
        return pass(
            "步态自洽：STEP_COUNTER 增量 ${formatSensorDouble(delta)}，STEP_DETECTOR 事件 $detectorEvents，" +
                "位移速度 ${formatSensorDouble(netSpeed)} m/s" +
                (if (ambiguous) "（0.3–0.5 m/s 模糊区间，未据此判定）" else "") +
                "；未接入 GMS Activity Recognition（P7 再做）。",
            evidence,
        )
    } catch (error: Throwable) {
        unknown(
            "C11 检查异常：${error.javaClass.simpleName}: ${error.message}",
            listOf("异常：${error.javaClass.simpleName}"),
        )
    }
}

private data class SensorSpec(
    val type: Int,
    val label: String,
    val vectorMagnitude: Boolean = false,
    val requireVariance: Boolean = false,
    val magnitudeMin: Float? = null,
    val magnitudeMax: Float? = null,
    val counter: Boolean = false,
    val detector: Boolean = false,
    val sparseAllowed: Boolean = false,
)

private data class SensorFinding(val fatal: Boolean, val message: String)

private val SENSOR_SPECS = listOf(
    SensorSpec(
        type = Sensor.TYPE_ACCELEROMETER,
        label = "加速度",
        vectorMagnitude = true,
        requireVariance = true,
        magnitudeMin = 6.8f,
        magnitudeMax = 12.8f,
    ),
    SensorSpec(
        type = Sensor.TYPE_GYROSCOPE,
        label = "陀螺仪",
        vectorMagnitude = true,
        requireVariance = true,
    ),
    SensorSpec(
        type = Sensor.TYPE_MAGNETIC_FIELD,
        label = "磁力",
        vectorMagnitude = true,
        requireVariance = true,
        magnitudeMin = 10f,
        magnitudeMax = 100f,
    ),
    SensorSpec(
        type = Sensor.TYPE_PRESSURE,
        label = "气压",
        magnitudeMin = 300f,
        magnitudeMax = 1100f,
    ),
    SensorSpec(
        type = Sensor.TYPE_STEP_COUNTER,
        label = "计步",
        counter = true,
        sparseAllowed = true,
    ),
    SensorSpec(
        type = Sensor.TYPE_STEP_DETECTOR,
        label = "步数事件",
        detector = true,
        sparseAllowed = true,
    ),
)

private fun inspectSensor(
    spec: SensorSpec,
    hasHardware: Boolean,
    samples: List<FloatArray>,
    activityPermission: Boolean,
    evidence: MutableList<String>,
): SensorFinding? {
    val label = spec.label
    if (!hasHardware && samples.isEmpty()) {
        addSensorEvidence(evidence, "$label：无硬件，跳过")
        return null
    }
    if (samples.isEmpty()) {
        if (spec.detector) {
            addSensorEvidence(evidence, "$label：有硬件但窗口内无事件（事件型传感器静止时正常）")
            return null
        }
        if (spec.counter && !activityPermission) {
            addSensorEvidence(evidence, "$label：有硬件但无数据；ACTIVITY_RECOGNITION 未授予/未声明，不判 FAIL")
            return null
        }
        addSensorEvidence(evidence, "$label：有硬件但 0 样本")
        return SensorFinding(true, "$label 有硬件但无数据")
    }
    if (!hasHardware) {
        addSensorEvidence(evidence, "$label：SensorManager 无默认传感器，却收到 ${samples.size} 个样本")
        return SensorFinding(true, "$label 无硬件却有数据")
    }

    val finite = samples.all { sample -> sample.all { it.isFinite() } }
    val sentinel = samples.all { sample ->
        sample.all { value -> abs(value) < SENSOR_SENTINEL_EPS || abs(value + 1f) < SENSOR_SENTINEL_EPS }
    }
    val variance = sensorVarianceSum(samples)
    val magnitudes = samples.map { sample ->
        if (spec.vectorMagnitude) {
            var squared = 0.0
            for (axis in 0 until minOf(3, sample.size)) {
                val value = sample[axis].toDouble()
                squared += value * value
            }
            sqrt(squared)
        } else {
            (sample.firstOrNull() ?: Float.NaN).toDouble()
        }
    }
    val minMagnitude = magnitudes.minOrNull() ?: 0.0
    val maxMagnitude = magnitudes.maxOrNull() ?: 0.0
    val meanMagnitude = if (magnitudes.isEmpty()) 0.0 else magnitudes.sum() / magnitudes.size

    var line = "$label：n=${samples.size} 首=${sensorSampleText(samples.first())} " +
        "末=${sensorSampleText(samples.last())} 方差=${formatSensorDouble(variance)} " +
        "模长 min/mean/max=${formatSensorDouble(minMagnitude)}/" +
        "${formatSensorDouble(meanMagnitude)}/${formatSensorDouble(maxMagnitude)}"
    if (spec.counter) {
        val first = samples.first().firstOrNull() ?: 0f
        val last = samples.last().firstOrNull() ?: 0f
        line += " 增量=${formatSensorDouble((last - first).toDouble())}"
    }
    addSensorEvidence(evidence, line)

    val reasons = mutableListOf<String>()
    if (!finite) reasons += "含 NaN/Inf"
    if (sentinel) reasons += "所有值均为 0/-1（疑似 native 清零）"
    if (spec.requireVariance && samples.size >= 3 && variance <= SENSOR_VARIANCE_EPS) {
        reasons += "三轴方差为 0（常量）"
    }
    val low = spec.magnitudeMin
    val high = spec.magnitudeMax
    if (low != null && high != null && (meanMagnitude < low || meanMagnitude > high)) {
        reasons += "模长均值 ${formatSensorDouble(meanMagnitude)} 超出 " +
            "${formatSensorDouble(low.toDouble())}–${formatSensorDouble(high.toDouble())}"
    }
    if (spec.counter) {
        val counts = samples.map { (it.firstOrNull() ?: Float.NaN).toDouble() }
        if (counts.any { it < 0.0 }) reasons += "计步为负"
        for (index in 1 until counts.size) {
            if (counts[index] < counts[index - 1]) {
                reasons += "计步回退（${formatSensorDouble(counts[index - 1])}→${formatSensorDouble(counts[index])}）"
                break
            }
        }
    }
    if (spec.detector) {
        val invalidEvents = samples.count { (it.firstOrNull() ?: 0f) <= 0f }
        if (invalidEvents > 0) reasons += "$invalidEvents 个事件值非正"
    }

    if (reasons.isNotEmpty()) {
        addSensorEvidence(evidence, "  异常：${reasons.joinToString("；").take(240)}")
        return SensorFinding(true, "$label：${reasons.joinToString("；")}")
    }
    if (samples.size < 3 && !spec.sparseAllowed) {
        addSensorEvidence(evidence, "  样本不足 3，方差判定不完整")
        return SensorFinding(false, "$label 样本不足（n=${samples.size}）")
    }
    return null
}

private fun sensorVarianceSum(samples: List<FloatArray>): Double {
    if (samples.size < 2) return 0.0
    val axes = samples.minOf { it.size }
    if (axes <= 0) return 0.0
    var total = 0.0
    for (axis in 0 until axes) {
        var mean = 0.0
        for (sample in samples) mean += sample[axis]
        mean /= samples.size
        var sum = 0.0
        for (sample in samples) {
            val diff = sample[axis] - mean
            sum += diff * diff
        }
        total += sum / samples.size
    }
    return total
}

private fun sensorSampleText(sample: FloatArray): String =
    sample.joinToString(",") { formatSensorDouble(it.toDouble()) }

private fun addSensorEvidence(evidence: MutableList<String>, line: String) {
    if (evidence.size < SENSOR_MAX_EVIDENCE_LINES) evidence += line.take(SENSOR_MAX_EVIDENCE_CHARS)
}

private fun addActivityEvidence(evidence: MutableList<String>, line: String) {
    if (evidence.size < ACTIVITY_MAX_EVIDENCE_LINES) evidence += line.take(ACTIVITY_MAX_EVIDENCE_CHARS)
}

private fun formatSensorDouble(value: Double): String =
    if (!value.isFinite()) value.toString() else String.format(Locale.US, "%.2f", value)

private fun sampleDurationSeconds(snapshot: SampleSnapshot): Double {
    val wallSeconds = ((snapshot.endedAtMillis - snapshot.startedAtMillis).coerceAtLeast(0L)) / 1000.0
    if (wallSeconds > 0.0) return wallSeconds
    val times = snapshot.locations.map { it.time }
    return if (times.size >= 2) (times.max() - times.min()) / 1000.0 else 0.0
}

private fun selectSpeedLocations(snapshot: SampleSnapshot): List<Location> {
    val gps = snapshot.locationsFor(LocationManager.GPS_PROVIDER)
    if (gps.size >= 2) return gps
    val fused = snapshot.locationsFor("fused")
    if (fused.size >= 2) return fused
    val network = snapshot.locationsFor(LocationManager.NETWORK_PROVIDER)
    if (network.size >= 2) return network
    return snapshot.locations
}

private fun sensorHaversineMeters(from: Location, to: Location): Double {
    val lat1 = Math.toRadians(from.latitude)
    val lat2 = Math.toRadians(to.latitude)
    val deltaLat = lat2 - lat1
    val deltaLon = Math.toRadians(to.longitude - from.longitude)
    val h = sin(deltaLat / 2) * sin(deltaLat / 2) +
        cos(lat1) * cos(lat2) * sin(deltaLon / 2) * sin(deltaLon / 2)
    return 2 * 6_371_000.0 * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

private const val ACTIVITY_MIN_SECONDS = 20.0
private const val ACTIVITY_MIN_SPEED_SECONDS = 10.0
private const val ACTIVITY_MOVING_SPEED_MPS = 0.5
private const val ACTIVITY_STILL_SPEED_MPS = 0.3
private const val SENSOR_SENTINEL_EPS = 1e-6f
private const val SENSOR_VARIANCE_EPS = 1e-12
private const val SENSOR_MAX_EVIDENCE_LINES = 20
private const val SENSOR_MAX_EVIDENCE_CHARS = 299
private const val ACTIVITY_MAX_EVIDENCE_LINES = 20
private const val ACTIVITY_MAX_EVIDENCE_CHARS = 299
