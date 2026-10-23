package io.github.lecheeel.integrity.core

import kotlinx.serialization.Serializable
import kotlin.math.*

@Serializable data class LocationObservation(
    val provider: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val altitudeMeters: Double?,
    val speedMps: Float?,
    val bearingDegrees: Float?,
    val wallTimeMillis: Long,
    val elapsedNanos: Long,
    val receivedElapsedNanos: Long,
    val isMock: Boolean,
    val cached: Boolean = false,
    val precisePermission: Boolean = true,
    val verticalAccuracyMeters: Float? = null,
    val speedAccuracyMps: Float? = null,
    val bearingAccuracyDegrees: Float? = null,
    val requestedProvider: String? = null
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(provider.isNotBlank())
    }
    val ageMillis: Double get() = (receivedElapsedNanos - elapsedNanos) / 1_000_000.0
    val source: String get() = requestedProvider ?: provider
}

@Serializable data class Evidence(
    val code: String, val level: String, val title: String, val detail: String,
    val provider: String, val atMillis: Long
)
@Serializable data class TimelineEvent(val atMillis: Long, val type: String, val message: String)
@Serializable data class SourceStats(
    val provider: String, val samples: Long, val mockSamples: Long,
    val intervalP50Millis: Double?, val intervalP95Millis: Double?,
    val maxAgeMillis: Double, val retainedIntervals: Int
)
@Serializable data class SatelliteObservation(
    val constellation: Int, val svid: Int, val cn0DbHz: Float?,
    val elevationDegrees: Float?, val azimuthDegrees: Float?, val usedInFix: Boolean
)
@Serializable data class RawGnssObservation(
    val constellation: Int, val svid: Int, val cn0DbHz: Double?,
    val receivedSvTimeNanos: Long, val receivedSvTimeUncertaintyNanos: Long,
    val pseudorangeRateMps: Double?, val pseudorangeRateUncertaintyMps: Double?,
    val accumulatedDeltaRangeMeters: Double?, val accumulatedDeltaRangeState: Int,
    val state: Int, val timeOffsetNanos: Double?, val carrierFrequencyHz: Float?
)
@Serializable data class GnssClockObservation(
    val timeNanos: Long, val fullBiasNanos: Long?, val biasNanos: Double?,
    val discontinuityCount: Int, val elapsedRealtimeNanos: Long?
)
@Serializable data class GnssObservation(
    val statuses: Map<String, String> = emptyMap(),
    val satellites: List<SatelliteObservation> = emptyList(),
    val rawMeasurements: List<RawGnssObservation> = emptyList(),
    val clock: GnssClockObservation? = null,
    val nmea: List<String> = emptyList(),
    val statusCallbacks: Long = 0, val rawCallbacks: Long = 0, val nmeaCallbacks: Long = 0,
    val lastStatusElapsedNanos: Long? = null, val lastRawElapsedNanos: Long? = null
)
@Serializable data class SensorObservation(
    val accelerometerAvailable: Boolean = false, val gyroscopeAvailable: Boolean = false,
    val accelerometerEvents: Long = 0, val gyroscopeEvents: Long = 0,
    val acceleration: List<Float?> = emptyList(), val gyroscope: List<Float?> = emptyList(),
    val lastAccelerationElapsedNanos: Long? = null, val lastGyroscopeElapsedNanos: Long? = null
)
@Serializable data class CaptureHealth(
    val retainedSamples: Int = 0, val totalSamples: Long = 0, val liveSamples: Long = 0,
    val droppedSamples: Long = 0, val lastCallbackElapsedNanos: Long? = null,
    val processPssKb: Long? = null, val processCpuOneCorePercent: Double? = null
)
@Serializable data class SessionReport(
    val schemaVersion: Int = 1,
    val id: String,
    val appVersion: String,
    val revision: String,
    val label: String,
    val notes: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long? = null,
    val startedElapsedNanos: Long,
    val capturedElapsedNanos: Long,
    val status: String,
    val requestedIntervalMillis: Long,
    val device: Map<String, String>,
    val permissions: Map<String, Boolean>,
    val providerStates: Map<String, String>,
    val verdict: String,
    val samples: List<LocationObservation>,
    val sources: List<SourceStats>,
    val evidence: List<Evidence>,
    val timeline: List<TimelineEvent>,
    val gnss: GnssObservation,
    val sensors: SensorObservation,
    val health: CaptureHealth,
    val limitations: List<String> = listOf(
        "Observes only what this app receives; targeted hooks in another app may be invisible.",
        "No evidence is not proof of authentic location; system or in-process hooks can affect observations.",
        "GNSS, NMEA and sensor absence is not proof of spoofing.",
        "No geolocation database or independent radio-location ground truth is used.",
        "Samples retain the newest 2000 observations; per-source interval percentiles use the newest 1000 live callback intervals.",
        "GNSS and sensor fields are latest snapshots, not a complete raw continuous recording. NMEA retains the newest 80 sentences."
    )
)

/** Diagnostic rules, deliberately no authenticity score or irreversible fraud decision. */
class IntegrityAnalyzer(private val sampleLimit: Int = 2000) {
    init { require(sampleLimit > 0) }
    private data class Counter(var count: Long = 0, var mock: Long = 0, var maxAge: Double = 0.0,
        var lastReceived: Long? = null, val intervals: ArrayDeque<Double> = ArrayDeque())
    private val counters = linkedMapOf<String, Counter>()
    private val last = mutableMapOf<String, LocationObservation>()
    private val kept = ArrayDeque<LocationObservation>()
    private val findings = linkedMapOf<String, Evidence>()
    var totalSamples = 0L; private set
    var liveSamples = 0L; private set
    var lastCallbackNanos: Long? = null; private set
    fun observe(sample: LocationObservation) {
        totalSamples++
        kept.addLast(sample)
        while (kept.size > sampleLimit) kept.removeFirst()
        val counter = counters.getOrPut(sample.source) { Counter() }
        counter.count++
        if (sample.isMock) {
            counter.mock++
            add("MOCK_FLAG", "DIRECT", "观察到系统模拟标记", "isMock=true；这是本应用收到的标记，不能据此推断其他应用的读数。", sample)
        }
        counter.maxAge = maxOf(counter.maxAge, sample.ageMillis)
        if (sample.cached) return
        liveSamples++
        lastCallbackNanos = sample.receivedElapsedNanos
        counter.lastReceived?.let {
            val gap = (sample.receivedElapsedNanos - it) / 1_000_000.0
            if (gap >= 0) counter.intervals.addLast(gap)
            while (counter.intervals.size > 1000) counter.intervals.removeFirst()
        }
        counter.lastReceived = sample.receivedElapsedNanos
        if (sample.ageMillis > 10_000) add("STALE", "CAUTION", "定位样本较旧", "样本年龄超过 10 秒，可能为缓存、批量回调或发布延迟。", sample)
        if (sample.ageMillis < -1000) add("FUTURE", "CAUTION", "样本时间在接收时间之后", "样本的单调时间比本进程接收时间晚超过 1 秒。", sample)
        val previous = last[sample.source]
        if (previous != null) {
            val delta = (sample.elapsedNanos - previous.elapsedNanos) / 1_000_000_000.0
            if (delta < -0.001) add("TIME_REVERSED", "CAUTION", "样本单调时间倒退", "可能由旧样本、乱序回调或时间字段异常造成。", sample)
            if (delta > 0 && delta <= 30 && sample.precisePermission && previous.precisePermission) {
                val distance = distanceMeters(previous, sample)
                val margin = 4 * ((sample.accuracyMeters ?: 1000f) + (previous.accuracyMeters ?: 1000f))
                if (distance > maxOf(1000.0, margin.toDouble()) && distance / delta > 500) {
                    add("LARGE_JUMP", "CAUTION", "观察到高速位置跳变", "两点推算速度超过 500m/s 且距离超过精度容差；仅作实验提示，不证明模拟。", sample)
                }
                if (sample.latitude == previous.latitude && sample.longitude == previous.longitude && delta >= 0.5) {
                    add("REPEATED_COORDINATES", "INFO", "出现重复坐标", "连续样本坐标相同；静止、缓存和模拟均可能出现。", sample)
                }
            }
        }
        if (sample.precisePermission && sample.ageMillis in 0.0..10_000.0) {
            last.values.filter { it.source != sample.source && it.precisePermission &&
                abs(it.elapsedNanos - sample.elapsedNanos) < 2_000_000_000 }.forEach {
                val margin = maxOf(1000.0, 3.0 * ((sample.accuracyMeters ?: 1000f) + (it.accuracyMeters ?: 1000f)))
                if (distanceMeters(it, sample) > margin) add("SOURCE_DIFFERENCE", "CAUTION", "定位来源存在差异",
                    sample.provider + " 与 " + it.provider + " 同期坐标差异较大；来源不保证相互独立，网络定位也可能不准。", sample)
            }
        }
        last[sample.source] = sample
    }
    private fun add(code: String, level: String, title: String, detail: String, sample: LocationObservation) {
        findings[code + ":" + sample.source] = Evidence(code, level, title, detail, sample.source, sample.wallTimeMillis)
    }
    fun samples() = kept.toList()
    fun evidence() = findings.values.sortedBy { when (it.level) { "DIRECT" -> 0; "CAUTION" -> 1; else -> 2 } }
    fun verdict(): String = when {
        findings.values.any { it.level == "DIRECT" } -> "MOCK_OBSERVED"
        liveSamples == 0L -> "INSUFFICIENT"
        findings.values.any { it.level == "CAUTION" } -> "ANOMALIES_OBSERVED"
        else -> "NO_EXPLICIT_EVIDENCE"
    }
    fun sources() = counters.map { (name, c) -> SourceStats(name, c.count, c.mock,
        percentile(c.intervals.toList(), 0.5), percentile(c.intervals.toList(), 0.95), c.maxAge, c.intervals.size) }
    companion object {
        fun percentile(values: List<Double>, fraction: Double): Double? {
            require(fraction in 0.0..1.0)
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            return sorted[(ceil(fraction * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)]
        }
        fun distanceMeters(a: LocationObservation, b: LocationObservation): Double {
            val lat1 = Math.toRadians(a.latitude); val lat2 = Math.toRadians(b.latitude)
            val dlat = lat2 - lat1; val dlon = Math.toRadians(b.longitude - a.longitude)
            val h = sin(dlat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dlon / 2).pow(2)
            return 6_371_008.8 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }
    }
}
