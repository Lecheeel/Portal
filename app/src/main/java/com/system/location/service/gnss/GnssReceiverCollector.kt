package com.system.location.service.gnss

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssMeasurementsEvent
import android.location.GnssNavigationMessage
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import com.system.location.service.core.backend.BackendType
import com.system.location.service.runtime.ScenarioRuntime
import com.system.location.service.service.MockServiceHelper
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Capture only while all simulation backends are stopped. All callbacks run on the main looper. */
class GnssReceiverCollector(
    context: Context,
    private val simulationActive: () -> Boolean = { ScenarioRuntime.state.value.isActive },
) {
    private val context = context.applicationContext

    @Suppress("MissingPermission", "DEPRECATION")
    suspend fun capture(durationMillis: Long = 60_000L, onProgress: (Int) -> Unit = {}): GnssCapture =
        withContext(Dispatchers.Main.immediate) {
            require(durationMillis in 30_000L..300_000L) { "采集窗口必须为 30–300 秒" }
            ScenarioRuntime.awaitInitialized()
            check(!simulationActive()) { "请先停止模拟，再采集真实卫星" }
            check(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                "真实卫星采集需要精确位置权限"
            }
            val manager = checkNotNull(context.getSystemService(LocationManager::class.java)) { "定位服务不可用" }
            check(manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) { "请先开启系统定位" }
            val localGeneration = ScenarioRuntime.controller.startGeneration
            val systemState = readSystemState(manager, initialize = true)
            fun localActive() = ScenarioRuntime.state.value.isActive || simulationActive()
            val session = GnssCaptureSessionGuard(localGeneration, localActive(),
                ScenarioRuntime.state.value.backend != BackendType.MOCK_PROVIDER, systemState)
            session.checkLocal(ScenarioRuntime.controller.startGeneration, localActive())
            check(capturing.compareAndSet(false, true)) { "已有卫星采集任务" }

            val frames = mutableListOf<GnssObservationFrame>()
            val measurements = mutableListOf<GnssMeasurementBatch>()
            val navigation = mutableListOf<GnssNavigationObservation>()
            val notes = linkedSetOf<String>()
            val started = System.currentTimeMillis()
            val elapsedStart = SystemClock.elapsedRealtime()
            val handler = Handler(Looper.getMainLooper())
            var location: Location? = null
            var contaminated = false
            var accepting = true
            var statusRegistered = false
            var measurementRegistered = false
            var navigationRegistered = false
            var locationRegistered = false
            fun accepts(): Boolean {
                if (localActive() || ScenarioRuntime.controller.startGeneration != localGeneration) contaminated = true
                return accepting && !contaminated
            }
            val locationListener = LocationListener { sample ->
                if (sample.isMock) contaminated = true
                if (!accepts()) return@LocationListener
                val arrival = SystemClock.elapsedRealtimeNanos()
                val valid = sample.latitude in -90.0..90.0 && sample.longitude in -180.0..180.0 &&
                    sample.elapsedRealtimeNanos in 1..arrival &&
                    (!sample.hasAltitude() || sample.altitude.isFinite()) &&
                    (!sample.hasAccuracy() || sample.accuracy.isFinite() && sample.accuracy >= 0f) &&
                    (!sample.hasVerticalAccuracy() || sample.verticalAccuracyMeters.isFinite() && sample.verticalAccuracyMeters >= 0f) &&
                    (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !sample.hasElapsedRealtimeUncertaintyNanos() ||
                        sample.elapsedRealtimeUncertaintyNanos.isFinite() && sample.elapsedRealtimeUncertaintyNanos >= 0.0)
                if (valid) location = Location(sample)
                else notes += "真实定位包含无效坐标、单调时刻或精度，未将该定位用于卫星观察点"
            }
            val statusCallback = object : GnssStatus.Callback() {
                override fun onStarted() { if (accepts()) notes += "GNSS 引擎已启动" }
                override fun onStopped() { if (accepts()) notes += "GNSS 引擎已停止" }
                override fun onFirstFix(ttffMillis: Int) { if (accepts()) notes += "首次定位耗时：${ttffMillis}ms" }
                override fun onSatelliteStatusChanged(status: GnssStatus) {
                    if (!accepts() || frames.size >= GnssCapture.MAX_FRAMES) return
                    val capturedElapsed = SystemClock.elapsedRealtimeNanos()
                    val capturedAt = System.currentTimeMillis()
                    if (status.satelliteCount > 512) {
                        notes += "卫星状态超过 512 条信号记录，未保存该异常帧"
                        return
                    }
                    val signals = (0 until status.satelliteCount).mapNotNull { index ->
                        val constellation = constellation(status.getConstellationType(index)) ?: run {
                            notes += "未知星座类型：${status.getConstellationType(index)}"
                            return@mapNotNull null
                        }
                        runCatching {
                            val satellite = GnssSatellite(constellation, status.getSvid(index), status.getElevationDegrees(index),
                                status.getAzimuthDegrees(index), status.getCn0DbHz(index), status.usedInFix(index),
                                status.hasEphemerisData(index), status.hasAlmanacData(index),
                                if (status.hasCarrierFrequencyHz(index)) status.getCarrierFrequencyHz(index) else null)
                            GnssSignalObservation(satellite,
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && status.hasBasebandCn0DbHz(index))
                                    status.getBasebandCn0DbHz(index) else null)
                        }.onFailure { notes += "部分卫星状态字段无效：${it.javaClass.simpleName} ${it.message}" }.getOrNull()
                    }
                    val satellites = GnssSignalProjection.spacecraft(signals)
                    val observer = location?.takeIf {
                        capturedElapsed - it.elapsedRealtimeNanos in 0..10_000_000_000L
                    }
                    frames += GnssObservationFrame(
                        capturedAtMillis = capturedAt,
                        observer = observer?.let { Wgs84(it.latitude, it.longitude) },
                        altitudeMeters = observer?.takeIf { it.hasAltitude() }?.altitude,
                        satellites = satellites,
                        signalObservations = signals,
                        measurementsAvailable = measurements.isNotEmpty(),
                        navigationMessagesAvailable = navigation.isNotEmpty(),
                        capturedElapsedRealtimeNanos = capturedElapsed,
                        observerElapsedRealtimeNanos = observer?.elapsedRealtimeNanos,
                        observerElapsedRealtimeUncertaintyNanos = observer?.takeIf {
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && it.hasElapsedRealtimeUncertaintyNanos()
                        }?.elapsedRealtimeUncertaintyNanos,
                        observerHorizontalAccuracyMeters = observer?.takeIf { it.hasAccuracy() }?.accuracy?.toDouble(),
                        observerVerticalAccuracyMeters = observer?.takeIf { it.hasAltitude() && it.hasVerticalAccuracy() }
                            ?.verticalAccuracyMeters?.toDouble(),
                    )
                    onProgress(frames.size)
                }
            }
            val measurementCallback = object : GnssMeasurementsEvent.Callback() {
                override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
                    if (!accepts() || measurements.size >= GnssCapture.MAX_FRAMES) return
                    val capturedElapsed = SystemClock.elapsedRealtimeNanos()
                    val capturedAt = System.currentTimeMillis()
                    val clock = event.clock
                    val values = event.measurements.mapNotNull { sample ->
                        val constellation = constellation(sample.constellationType) ?: return@mapNotNull null
                        runCatching {
                            GnssMeasurementObservation(constellation, sample.svid, sample.state, sample.timeOffsetNanos,
                                sample.receivedSvTimeNanos, sample.receivedSvTimeUncertaintyNanos, sample.cn0DbHz,
                                sample.pseudorangeRateMetersPerSecond, sample.pseudorangeRateUncertaintyMetersPerSecond,
                                sample.accumulatedDeltaRangeState, sample.accumulatedDeltaRangeMeters,
                                sample.accumulatedDeltaRangeUncertaintyMeters,
                                if (sample.hasCarrierFrequencyHz()) sample.carrierFrequencyHz.toDouble() else null,
                                if (sample.hasCodeType()) sample.codeType else null,
                                fullInterSignalBiasNanos = if (sample.hasFullInterSignalBiasNanos()) sample.fullInterSignalBiasNanos else null,
                                fullInterSignalBiasUncertaintyNanos = if (sample.hasFullInterSignalBiasUncertaintyNanos())
                                    sample.fullInterSignalBiasUncertaintyNanos else null,
                                satelliteInterSignalBiasNanos = if (sample.hasSatelliteInterSignalBiasNanos())
                                    sample.satelliteInterSignalBiasNanos else null,
                                satelliteInterSignalBiasUncertaintyNanos = if (sample.hasSatelliteInterSignalBiasUncertaintyNanos())
                                    sample.satelliteInterSignalBiasUncertaintyNanos else null)
                        }.onFailure { notes += "部分原始测量字段无效：${it.javaClass.simpleName}" }.getOrNull()
                    }.take(512)
                    runCatching {
                        GnssMeasurementBatch(capturedAt, clock.timeNanos,
                            if (clock.hasFullBiasNanos()) clock.fullBiasNanos else null,
                            if (clock.hasBiasNanos()) clock.biasNanos else null,
                            clock.hardwareClockDiscontinuityCount, values,
                            timeUncertaintyNanos = if (clock.hasTimeUncertaintyNanos()) clock.timeUncertaintyNanos else null,
                            biasUncertaintyNanos = if (clock.hasBiasUncertaintyNanos()) clock.biasUncertaintyNanos else null,
                            driftNanosPerSecond = if (clock.hasDriftNanosPerSecond()) clock.driftNanosPerSecond else null,
                            driftUncertaintyNanosPerSecond = if (clock.hasDriftUncertaintyNanosPerSecond()) clock.driftUncertaintyNanosPerSecond else null,
                            leapSecond = if (clock.hasLeapSecond()) clock.leapSecond else null,
                            elapsedRealtimeNanos = if (Build.VERSION.SDK_INT >= 29 && clock.hasElapsedRealtimeNanos()) clock.elapsedRealtimeNanos else null,
                            elapsedRealtimeUncertaintyNanos = if (Build.VERSION.SDK_INT >= 29 && clock.hasElapsedRealtimeUncertaintyNanos()) clock.elapsedRealtimeUncertaintyNanos else null,
                            capturedElapsedRealtimeNanos = capturedElapsed,
                            referenceConstellationTypeForIsb = if (clock.hasReferenceConstellationTypeForIsb())
                                clock.referenceConstellationTypeForIsb else null,
                            referenceCarrierHzForIsb = if (clock.hasReferenceCarrierFrequencyHzForIsb())
                                clock.referenceCarrierFrequencyHzForIsb else null,
                            referenceCodeTypeForIsb = if (clock.hasReferenceCodeTypeForIsb()) clock.referenceCodeTypeForIsb else null)
                    }.onSuccess { measurements += it }
                        .onFailure { notes += "原始接收机时钟字段无效：${it.message}" }
                }
                override fun onStatusChanged(status: Int) { notes += "原始测量状态：$status" }
            }
            val navigationCallback = object : GnssNavigationMessage.Callback() {
                override fun onGnssNavigationMessageReceived(event: GnssNavigationMessage) {
                    if (!accepts() || navigation.size >= GnssCapture.MAX_NAVIGATION_MESSAGES) return
                    val capturedElapsed = SystemClock.elapsedRealtimeNanos()
                    val capturedAt = System.currentTimeMillis()
                    runCatching {
                        GnssNavigationObservation(capturedAt, event.type, event.svid, event.status,
                            event.messageId, event.submessageId, Base64.getEncoder().encodeToString(event.data), capturedElapsed)
                    }.onSuccess { navigation += it }
                        .onFailure { notes += "导航电文字段无效：${it.javaClass.simpleName}" }
                }
                override fun onStatusChanged(status: Int) { notes += "导航电文状态：$status" }
            }
            try {
                // HAL capability flags, registration acceptance and actual received data are
                // independent evidence. A successful registration does not imply hardware support.
                runCatching {
                    val capabilities = manager.gnssCapabilities
                    notes += "系统声明 GNSS 能力：原始测量=${capabilities.hasMeasurements()}，导航电文=${capabilities.hasNavigationMessages()}"
                }.onFailure { notes += "GNSS 能力读取失败：${it.javaClass.simpleName}" }
                runCatching {
                    notes += "GNSS 驱动：${manager.gnssHardwareModelName ?: "未提供"}；硬件年份=${manager.gnssYearOfHardware}（0 表示未提供 2016 年及以后的年份）"
                }.onFailure { notes += "GNSS 驱动信息读取失败：${it.javaClass.simpleName}" }
                statusRegistered = manager.registerGnssStatusCallback(statusCallback, handler)
                check(statusRegistered) { "系统拒绝注册卫星状态监听" }
                measurementRegistered = runCatching {
                    manager.registerGnssMeasurementsCallback(measurementCallback, handler)
                }.onFailure { notes += "原始测量不可用：${it.javaClass.simpleName}" }.getOrDefault(false)
                notes += "原始测量监听注册：${if (measurementRegistered) "已接受" else "未接受"}"
                navigationRegistered = runCatching {
                    manager.registerGnssNavigationMessageCallback(navigationCallback, handler)
                }.onFailure { notes += "导航电文不可用：${it.javaClass.simpleName}" }.getOrDefault(false)
                notes += "导航电文监听注册：${if (navigationRegistered) "已接受" else "未接受"}"
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper())
                locationRegistered = true
                var lastSystemCheck = SystemClock.elapsedRealtime()
                while (SystemClock.elapsedRealtime() - elapsedStart < durationMillis) {
                    delay(250L)
                    check(accepts()) { "采集期间启动了模拟，已废弃本次采集" }
                    if (SystemClock.elapsedRealtime() - lastSystemCheck >= 1_000L) {
                        session.checkSystem(readSystemState(manager, initialize = false))
                        lastSystemCheck = SystemClock.elapsedRealtime()
                    }
                }
                // Freeze the buffer before the final Binder round trip. A start after the final
                // observation cannot introduce any further callbacks into the accepted window.
                accepting = false
                session.checkSystem(readSystemState(manager, initialize = systemState == null))
                session.checkLocal(ScenarioRuntime.controller.startGeneration, localActive())
                check(!contaminated) { "采集期间收到模拟定位，已废弃本次采集" }
                check(frames.isNotEmpty()) {
                    "未收到卫星状态帧（测量批次 ${measurements.size}、导航电文 ${navigation.size}）；" +
                        "${notes.joinToString("；")}" + "。请检查接收环境和系统定位后重新采集"
                }
                // A nonempty acquisition window can contain only zero-CN0 rows. Return
                // that evidence so the repository can archive it without promoting it
                // to a playback profile. Quality gating belongs at that boundary.
                if (frames.none { it.satellites.any(GnssCaptureValidator::isUsableSatellite) }) {
                    notes += "未收到正 CN0 且几何有效的信号；本窗口仅供诊断，不可用于画像回放"
                }
                check(System.currentTimeMillis() >= frames.last().capturedAtMillis &&
                    frames.zipWithNext().all { (a, b) -> a.capturedAtMillis <= b.capturedAtMillis }) {
                    "采集期间系统时间倒退，请重新采集"
                }
                GnssCapture(deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}", osVersion = "${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}",
                    startedAtMillis = started, endedAtMillis = System.currentTimeMillis(), frames = frames.toList(),
                    measurements = measurements.toList(), navigation = navigation.toList(), notes = notes.toList(),
                    bootCount = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }
                        .getOrNull()?.takeIf { it >= 0 })
            } finally {
                accepting = false
                if (statusRegistered) runCatching { manager.unregisterGnssStatusCallback(statusCallback) }
                if (measurementRegistered) runCatching { manager.unregisterGnssMeasurementsCallback(measurementCallback) }
                if (navigationRegistered) runCatching { manager.unregisterGnssNavigationMessageCallback(navigationCallback) }
                if (locationRegistered) runCatching { manager.removeUpdates(locationListener) }
                capturing.set(false)
            }
        }

    @Suppress("DEPRECATION")
    private suspend fun readSystemState(manager: LocationManager, initialize: Boolean): GnssCaptureSystemState? =
        withContext(Dispatchers.IO) {
            if (initialize && !MockServiceHelper.isServiceInit()) MockServiceHelper.tryInitService(manager)
            var reply = runCatching { MockServiceHelper.runtimeStatus(manager) }.getOrNull()
            if (initialize && reply == null && MockServiceHelper.isServiceInit()) {
                // A stale token may be refreshed before collecting any receiver evidence.
                MockServiceHelper.tryInitService(manager)
                reply = runCatching { MockServiceHelper.runtimeStatus(manager) }.getOrNull()
            }
            val status = reply ?: return@withContext null
            val instance = status.get("runtime_instance")
            val generation = status.get("simulation_generation")
            val active = status.get("is_start")
            check(status.getString("hook_scope") == "SYSTEM_SERVER" && instance is String &&
                instance.isNotBlank() && instance.length <= 256 && generation is Long && generation >= 0 && active is Boolean) {
                "系统未提供可核对的模拟状态，请更新并重新加载模块后采集"
            }
            GnssCaptureSystemState(instance, generation, active)
        }

    companion object {
        private val capturing = AtomicBoolean(false)

        internal fun constellation(code: Int): Constellation? = when (code) {
            GnssStatus.CONSTELLATION_GPS -> Constellation.GPS
            GnssStatus.CONSTELLATION_BEIDOU -> Constellation.BEIDOU
            GnssStatus.CONSTELLATION_GLONASS -> Constellation.GLONASS
            GnssStatus.CONSTELLATION_GALILEO -> Constellation.GALILEO
            GnssStatus.CONSTELLATION_QZSS -> Constellation.QZSS
            GnssStatus.CONSTELLATION_SBAS -> Constellation.SBAS
            GnssStatus.CONSTELLATION_IRNSS -> Constellation.IRNSS
            else -> null
        }
    }
}
