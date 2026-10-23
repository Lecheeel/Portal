package io.github.lecheeel.integrity

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.*
import android.location.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.lecheeel.integrity.core.*
import kotlinx.coroutines.*
import java.util.UUID

/** Mutable observation state is main-looper confined; storage never blocks location callbacks. */
class ObservationService : Service(), SensorEventListener {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val disk = Dispatchers.IO.limitedParallelism(1)
    private lateinit var manager: LocationManager
    private lateinit var sensors: SensorManager
    private lateinit var repository: ReportRepository
    private val analyzer = IntegrityAnalyzer()
    private var base: SessionReport? = null
    private var active = false
    private var finishing = false
    private val listeners = mutableListOf<LocationListener>()
    private val providers = linkedMapOf<String, String>()
    private val events = ArrayDeque<TimelineEvent>()
    private var gnss = GnssObservation()
    private var sensor = SensorObservation()
    private var pss: Long? = null
    private var cpu: Double? = null
    private var lastHealthAt = 0L
    private var lastCpuAt = 0L
    private var lastCheckpointAt = 0L
    private var readingHealth = false
    private val ticker = object : Runnable {
        override fun run() {
            if (!active) return
            if (!CapturePermissions.location(this@ObservationService) ||
                CapturePermissions.has(this@ObservationService, Manifest.permission.ACCESS_FINE_LOCATION) != base?.permissions?.get("fineLocation")) {
                event("PERMISSION", "定位权限发生变化，结束本次实验；请按新权限重新开始")
                finishCapture("PERMISSION_CHANGED"); return
            }
            updateView()
            val now = SystemClock.elapsedRealtime()
            if (now - lastHealthAt >= 10_000 && !readingHealth) {
                readingHealth = true
                scope.launch {
                    val used = withContext(Dispatchers.IO) { Debug.getPss() }
                    val processCpu = Process.getElapsedCpuTime()
                    val since = now - lastHealthAt
                    if (lastHealthAt > 0 && since > 0) cpu = ((processCpu - lastCpuAt) * 100.0 / since).coerceAtLeast(0.0)
                    pss = used; lastHealthAt = now; lastCpuAt = processCpu; readingHealth = false
                }
            }
            if (now - lastCheckpointAt >= 15_000) { lastCheckpointAt = now; CaptureStore.view.report?.let { persist(it) } }
            handler.postDelayed(this, 1000)
        }
    }
    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(LocationManager::class.java); sensors = getSystemService(SensorManager::class.java)
        repository = ReportRepository(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "定位观测", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> finishCapture("STOPPED")
            MARK -> if (active) { event("MARK", intent.getStringExtra("text").orEmpty().take(500)); updateView() }
            START -> if (!active && !finishing) startCapture(intent)
            else -> if (!active) stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun startCapture(intent: Intent) {
        if (!CapturePermissions.location(this)) { CaptureStore.view = CaptureView(message = "未授予定位权限，未开始观测"); stopSelf(); return }
        try {
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(this, 1, Intent(this, ObservationService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_integrity)
                .setContentTitle("Location Integrity · 正在观测").setContentText("仅记录本应用收到的数据；点击打开，或停止并保存")
                .setContentIntent(open).setOngoing(true).addAction(0, "停止并保存", stop).build()
            ServiceCompat.startForeground(this, 3101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            val now = SystemClock.elapsedRealtimeNanos()
            base = SessionReport(id = UUID.randomUUID().toString(), appVersion = BuildConfig.VERSION_NAME,
                revision = BuildConfig.REVISION, label = intent.getStringExtra("label").orEmpty().take(80),
                notes = intent.getStringExtra("notes").orEmpty().take(2000), startedAtMillis = System.currentTimeMillis(),
                startedElapsedNanos = now, capturedElapsedNanos = now, status = "RUNNING",
                requestedIntervalMillis = intent.getLongExtra("interval", 1000).coerceIn(250, 5000),
                device = linkedMapOf("manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL,
                    "sdk" to Build.VERSION.SDK_INT.toString(), "android" to Build.VERSION.RELEASE,
                    "build" to Build.DISPLAY, "securityPatch" to Build.VERSION.SECURITY_PATCH),
                permissions = CapturePermissions.snapshot(this), providerStates = emptyMap(), verdict = "INSUFFICIENT",
                samples = emptyList(), sources = emptyList(), evidence = emptyList(), timeline = emptyList(),
                gnss = GnssObservation(), sensors = SensorObservation(), health = CaptureHealth())
            active = true; event("START", "开始观测；手动测试标签不参与检测判断")
            registerSources(); registerGnss(); registerSensors(); updateView(); handler.post(ticker)
        } catch (error: Exception) {
            event("ERROR", "启动失败：" + error.javaClass.simpleName + " " + error.message)
            if (base != null) finishCapture("ERROR") else { CaptureStore.view = CaptureView(message = "启动失败：" + error.message); stopSelf() }
        }
    }
    @SuppressLint("MissingPermission")
    private fun registerSources() {
        val available = manager.allProviders.toSet()
        for (name in listOf("gps", "network", "passive", "fused")) {
            if (name !in available) { providers[name] = "系统未提供"; continue }
            try {
                providers[name] = if (manager.isProviderEnabled(name)) "已订阅，等待样本" else "来源已关闭，等待启用"
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) { receive(name, location, false) }
                    override fun onProviderDisabled(provider: String) { providers[name] = "来源已关闭"; event("PROVIDER", name + " 已关闭") }
                    override fun onProviderEnabled(provider: String) { providers[name] = "已启用，等待样本"; event("PROVIDER", name + " 已启用") }
                    @Deprecated("Platform callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                }
                manager.requestLocationUpdates(name, base!!.requestedIntervalMillis, 0f, listener, Looper.getMainLooper())
                listeners += listener
                manager.getLastKnownLocation(name)?.let { receive(name, it, true) }
            } catch (error: SecurityException) { providers[name] = "未授权：" + error.javaClass.simpleName }
            catch (error: Exception) { providers[name] = "注册失败：" + error.javaClass.simpleName; event("PROVIDER_ERROR", name + ": " + error.message) }
        }
    }
    private fun receive(requested: String, location: Location, cached: Boolean) {
        if (!active) return
        try {
            val sample = LocationObservation(location.provider ?: requested, location.latitude, location.longitude,
                location.accuracy.takeIf { location.hasAccuracy() && it.isFinite() }, location.altitude.takeIf { location.hasAltitude() && it.isFinite() },
                location.speed.takeIf { location.hasSpeed() && it.isFinite() }, location.bearing.takeIf { location.hasBearing() && it.isFinite() },
                location.time, location.elapsedRealtimeNanos, SystemClock.elapsedRealtimeNanos(),
                if (Build.VERSION.SDK_INT >= 31) location.isMock else location.isFromMockProvider,
                cached, CapturePermissions.has(this, Manifest.permission.ACCESS_FINE_LOCATION),
                location.verticalAccuracyMeters.takeIf { location.hasVerticalAccuracy() && it.isFinite() },
                location.speedAccuracyMetersPerSecond.takeIf { location.hasSpeedAccuracy() && it.isFinite() },
                location.bearingAccuracyDegrees.takeIf { location.hasBearingAccuracy() && it.isFinite() }, requestedProvider = requested)
            val before = analyzer.evidence().map { it.code + it.provider }.toSet()
            analyzer.observe(sample)
            analyzer.evidence().filter { it.code + it.provider !in before }.forEach { event("EVIDENCE", it.title + " · " + it.provider) }
            if (!cached) providers[requested] = "收到实时回调；报告来源=" + sample.provider
        } catch (error: Exception) { event("INVALID_SAMPLE", requested + ": " + error.javaClass.simpleName) }
    }
    private fun status(name: String, value: String) { gnss = gnss.copy(statuses = gnss.statuses + (name to value)) }
    private val satelliteCallback = object : GnssStatus.Callback() {
        override fun onStarted() { status("status", "GNSS 已启动，等待卫星状态") }
        override fun onStopped() { status("status", "GNSS 已停止") }
        override fun onFirstFix(ttffMillis: Int) { event("GNSS_FIX", "首次定位耗时 " + ttffMillis + " ms") }
        override fun onSatelliteStatusChanged(value: GnssStatus) {
            val items = (0 until minOf(value.satelliteCount, 128)).map { i -> SatelliteObservation(value.getConstellationType(i), value.getSvid(i),
                value.getCn0DbHz(i).finite(), value.getElevationDegrees(i).finite(), value.getAzimuthDegrees(i).finite(), value.usedInFix(i)) }
            gnss = gnss.copy(satellites = items, statusCallbacks = gnss.statusCallbacks + 1, lastStatusElapsedNanos = SystemClock.elapsedRealtimeNanos())
            status("status", "已收到卫星状态")
        }
    }
    private val measurementCallback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(eventArgs: GnssMeasurementsEvent) {
            val items = eventArgs.measurements.take(128).map { m -> RawGnssObservation(m.constellationType, m.svid, m.cn0DbHz.finite(),
                m.receivedSvTimeNanos, m.receivedSvTimeUncertaintyNanos, m.pseudorangeRateMetersPerSecond.finite(),
                m.pseudorangeRateUncertaintyMetersPerSecond.finite(), m.accumulatedDeltaRangeMeters.finite(), m.accumulatedDeltaRangeState,
                m.state, m.timeOffsetNanos.finite(), m.carrierFrequencyHz.takeIf { m.hasCarrierFrequencyHz() && it.isFinite() }) }
            val c = eventArgs.clock
            gnss = gnss.copy(rawMeasurements = items, clock = GnssClockObservation(c.timeNanos,
                c.fullBiasNanos.takeIf { c.hasFullBiasNanos() }, c.biasNanos.takeIf { c.hasBiasNanos() && it.isFinite() }, c.hardwareClockDiscontinuityCount,
                if (Build.VERSION.SDK_INT >= 29 && c.hasElapsedRealtimeNanos()) c.elapsedRealtimeNanos else null),
                rawCallbacks = gnss.rawCallbacks + 1, lastRawElapsedNanos = SystemClock.elapsedRealtimeNanos())
            status("raw", "已收到原始测量")
        }
        @Deprecated("Platform callback") override fun onStatusChanged(value: Int) { status("raw", when (value) { 0 -> "设备报告不支持"; 1 -> "就绪，等待测量"; 2 -> "定位已关闭"; else -> "状态 " + value }) }
    }
    private val nmeaListener = OnNmeaMessageListener { message, timestamp ->
        if (active) {
            gnss = gnss.copy(nmea = (gnss.nmea + (timestamp.toString() + " " + message.trim().take(512))).takeLast(80), nmeaCallbacks = gnss.nmeaCallbacks + 1)
            status("nmea", "已收到 NMEA")
        }
    }
    @SuppressLint("MissingPermission") private fun registerGnss() {
        if (!CapturePermissions.has(this, Manifest.permission.ACCESS_FINE_LOCATION)) {
            for (name in listOf("status", "raw", "nmea")) status(name, "缺少精确定位权限，未订阅")
            return
        }
        for (name in listOf("status", "raw", "nmea")) {
            try {
                val accepted = when (name) {
                    "status" -> manager.registerGnssStatusCallback(satelliteCallback, handler)
                    "raw" -> manager.registerGnssMeasurementsCallback(measurementCallback, handler)
                    else -> manager.addNmeaListener(nmeaListener, handler)
                }
                status(name, if (accepted) "已注册，尚无回调（不代表模拟）" else "注册被系统拒绝或不支持")
            } catch (error: Exception) { status(name, "不可用：" + error.javaClass.simpleName) }
        }
    }
    private fun registerSensors() {
        val acceleration = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        sensor = sensor.copy(accelerometerAvailable = acceleration?.let { sensors.registerListener(this, it, 200_000, handler) } ?: false,
            gyroscopeAvailable = gyro?.let { sensors.registerListener(this, it, 200_000, handler) } ?: false)
    }
    override fun onSensorChanged(event: SensorEvent) {
        if (!active) return
        val values = event.values.take(3).map { it.finite() }
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) sensor = sensor.copy(accelerometerEvents = sensor.accelerometerEvents + 1,
            acceleration = values, lastAccelerationElapsedNanos = event.timestamp)
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) sensor = sensor.copy(gyroscopeEvents = sensor.gyroscopeEvents + 1,
            gyroscope = values, lastGyroscopeElapsedNanos = event.timestamp)
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    private fun event(type: String, message: String) { events.addLast(TimelineEvent(System.currentTimeMillis(), type, message.take(800))); while (events.size > 300) events.removeFirst() }
    private fun report(status: String): SessionReport? = base?.copy(capturedElapsedNanos = SystemClock.elapsedRealtimeNanos(),
        endedAtMillis = if (status == "RUNNING") null else System.currentTimeMillis(), status = status,
        permissions = CapturePermissions.snapshot(this), providerStates = providers.toMap(), verdict = analyzer.verdict(),
        samples = analyzer.samples(), sources = analyzer.sources(), evidence = analyzer.evidence(), timeline = events.toList(), gnss = gnss, sensors = sensor,
        health = CaptureHealth(analyzer.samples().size, analyzer.totalSamples, analyzer.liveSamples, (analyzer.totalSamples - analyzer.samples().size).coerceAtLeast(0), analyzer.lastCallbackNanos, pss, cpu))
    private fun updateView() { val current = report("RUNNING") ?: return; CaptureStore.view = CaptureView(true, current, "正在观测；数据仅保存在本机", CaptureStore.view.storageError) }
    private fun persist(report: SessionReport) { scope.launch(disk) {
        try { repository.save(report) }
        catch (error: Exception) { withContext(Dispatchers.Main) { CaptureStore.view = CaptureStore.view.copy(storageError = "保存失败：" + error.message) } }
    } }
    private fun finishCapture(status: String) {
        if (finishing) return
        if (active) {
            active = false; finishing = true; unregister(); handler.removeCallbacks(ticker); event("STOP", "观测结束：" + status)
            val final = report(status); CaptureStore.view = CaptureView(false, final, "观测已结束，正在保存", CaptureStore.view.storageError, saving = true)
            scope.launch {
                if (final != null) {
                    try { withContext(disk) { repository.save(final) }; CaptureStore.view = CaptureStore.view.copy(message = "已保存至本机实验记录") }
                    catch (error: Exception) { CaptureStore.view = CaptureStore.view.copy(storageError = "保存失败：" + error.message) }
                }
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
        } else { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }
    private fun unregister() {
        listeners.forEach { runCatching { manager.removeUpdates(it) } }; listeners.clear()
        runCatching { manager.unregisterGnssStatusCallback(satelliteCallback) }; runCatching { manager.unregisterGnssMeasurementsCallback(measurementCallback) }
        runCatching { manager.removeNmeaListener(nmeaListener) }; sensors.unregisterListener(this)
    }
    override fun onDestroy() {
        if (active) {
            active = false; unregister(); handler.removeCallbacks(ticker); event("INTERRUPTED", "服务被系统销毁；不自动重启")
            val interrupted = report("INTERRUPTED"); CaptureStore.view = CaptureView(false, interrupted, "观测已中断")
            if (interrupted != null) CoroutineScope(SupervisorJob() + disk).launch { runCatching { repository.save(interrupted) } }
        }
        CaptureStore.view = CaptureStore.view.copy(saving = false)
        scope.cancel(); super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun Float.finite(): Float? = takeIf { isFinite() }
    private fun Double.finite(): Double? = takeIf { isFinite() }
    companion object {
        private const val CHANNEL = "integrity_observation"
        const val START = "integrity.START"; const val STOP = "integrity.STOP"; const val MARK = "integrity.MARK"
        fun stop(context: Context) = context.startService(Intent(context, ObservationService::class.java).setAction(STOP))
        fun mark(context: Context, text: String) = context.startService(Intent(context, ObservationService::class.java).setAction(MARK).putExtra("text", text))
    }
}
