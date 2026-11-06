package com.system.location.service.hook.utils

import android.location.Location
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import android.os.SystemClock
import kotlin.math.asin
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.location.LocationSample

object FakeLoc {
    /**
     * 是否允许打印日志
     */
    var enableLog = true

    /**
     * 是否允许打印调试日志
     */
    var enableDebugLog = false

    /**
     * 模拟定位服务开关
     */
    @Volatile
    var enable = false

    /**
     * 模拟Gnss卫星数据开关（默认开启，仅在模拟定位启动时生效）
     */
    @Volatile
    var enableMockGnss = true

    /**
     * 模拟WLAN数据（默认开启，仅在模拟定位启动时生效）
     */
    @Volatile
    var enableMockWifi = true

    /**
     * 是否禁用GetCurrentLocation方法（在部分系统不禁用可能导致hook失效）
     */
    var disableGetCurrentLocation = false

    /**
     * 是否禁用RegisterLocationListener方法
     */
    var disableRegisterLocationListener = false

    /**
     * 如果TelephonyHook失效，可能需要打开此开关
     */
    var disableFusedLocation = false
    var disableNetworkLocation = true

    var disableRequestGeofence = false
    var disableGetFromLocation = false

    /**
     * 是否允许AGPS模块（当前没什么鸟用）
     */
    var enableAGPS = false

    /**
     * 是否允许NMEA模块
     */
    var enableNMEA = false

    /**
     * 是否隐藏模拟位置
     */
    var hideMock = true

    /**
     * may cause system to crash
     */
    var hookWifi = true

    /**
     * 将网络定位降级为 Cdma（ADR-005：旧路径默认关闭，下一版本删除）
     */
    var needDowngradeToCdma = false
    var isSystemServerProcess = false

    /**
     * 模拟最小卫星数量
     */
    var minSatellites = 12

    /** 环境生成 seed；同一位置、同一 seed 必须生成同一组 AP/基站（ADR-022）。 */
    @Volatile
    var environmentSeed = 0L

    /** Captured WiFi/cell profile used for replay; null keeps the procedural source. */
    @Volatile
    var replayEnvironment: com.system.location.service.core.environment.EnvironmentProfile? = null

    /** Optional bounded OU drift; disabled by default and never applied to real locations. */
    @Volatile
    var enablePhysicalNoise = false
    @Volatile
    var physicalNoiseSeed = 0L

    /** P2-05: false means NO_SIM_WIFI_ONLY; default true preserves real/current behavior. */
    @Volatile
    var simReady = true

    /** P3-05: analytical production model by default; ORBITAL is a feasibility experiment. */
    @Volatile
    var gnssModelMode = com.system.location.service.core.gnss.GnssModelMode.ANALYTIC

    /** P5-02: null/global keeps every listener on the current fake fix. */
    @Volatile
    var routingConfig: com.system.location.service.core.routing.RoutingConfig? = null

    /**
     * 反定位复原加强（启用后将导致部分应用在关闭LocationService后需要重新启动才能重新获取定位）
     */
    var loopBroadcastLocation = false

    /**
     * 上一次的位置
     */
    @Volatile var lastLocation: Location? = null
    @Volatile private var coordinates = 0.0 to 0.0
    val latitude get() = coordinates.first
    val longitude get() = coordinates.second
    fun coordinatePair(): Pair<Double, Double> = coordinates
    @Volatile var altitude = 80.0

    @Volatile var speed = 3.05

    var speedAmplitude = 1.0

    @Volatile var hasBearings = false

    @Volatile var bearing = 0.0
    @Volatile var reportIntervalMs = 100L
    private var sample: LocationSample? = null
    /** P5-02: per-scenario samples; routing falls back to the active fix when a scenario has none. */
    val scenarioSamples = com.system.location.service.core.runtime.ScenarioSampleStore()

    @Synchronized
    fun acceptScenarioSample(scenarioId: String, fix: LocationSample) {
        scenarioSamples.publish(scenarioId, fix, SystemClock.elapsedRealtimeNanos())
    }

    @Synchronized
    fun snapshotForScenario(
        scenarioId: String?,
        force: Boolean = false,
        now: Long = SystemClock.elapsedRealtimeNanos(),
        timeMillis: Long = System.currentTimeMillis(),
    ): LocationSample {
        if (scenarioId == null) return snapshot(force, now, timeMillis)
        return scenarioSamples.latest(scenarioId, now)?.sample ?: snapshot(force, now, timeMillis)
    }

    @Volatile var externallyDriven = false
        private set
    private var lastSampleNanos = 0L
    private var lastMovementNanos = 0L
    private var movementSpeed = 0f

    @Synchronized
    fun updateCoordinates(lat: Double, lon: Double, movingSpeed: Float = 0f, nowNanos: Long = SystemClock.elapsedRealtimeNanos()) {
        require(lat in -90.0..90.0 && lon in -180.0..180.0)
        coordinates = lat to lon
        movementSpeed = movingSpeed.coerceAtLeast(0f)
        lastMovementNanos = nowNanos
        sample = null
        externallyDriven = false
    }

    @Synchronized
    fun acceptSample(fix: LocationSample) {
        coordinates = fix.latitude to fix.longitude
        altitude = fix.altitude
        accuracy = fix.accuracy
        speed = fix.speed.toDouble()
        bearing = fix.bearing.toDouble()
        hasBearings = true
        sample = fix
        externallyDriven = true
    }

    @Synchronized
    fun snapshot(force: Boolean = false, now: Long = SystemClock.elapsedRealtimeNanos(), timeMillis: Long = System.currentTimeMillis()): LocationSample {
        sample?.let {
            if (externallyDriven) return it
            if (!force && now - it.elapsedNanos < reportIntervalMs.coerceIn(50, 1000) * 1_000_000) return it
        }
        val point = coordinates
        lastSampleNanos = maxOf(now, lastSampleNanos + 1)
        return LocationSample(
            Wgs84(point.first, point.second), altitude, accuracy.coerceAtLeast(0.1f),
            if (now - lastMovementNanos <= maxOf(500L, reportIntervalMs * 2) * 1_000_000) movementSpeed else 0f,
            ((bearing % 360 + 360) % 360).toFloat().coerceAtMost(359.99997f),
            timeMillis, lastSampleNanos,
        ).also { sample = it }
    }

    @Volatile var accuracy = 25.0f
        set(value) {
            field = if (value < 0) {
                -value
            } else {
                value
            }
        }

    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val radius = 6371000.0
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaPhi = Math.toRadians(lat2 - lat1)
        val deltaLambda = Math.toRadians(lon2 - lon1)
        val a = sin(deltaPhi / 2).pow(2) + cos(phi1) * cos(phi2) * sin(deltaLambda / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return radius * c
    }

    // Legacy callers now read the same fixed coordinate; accuracy is metadata, not noise.
    fun jitterLocation(): Pair<Double, Double> = coordinatePair()

    fun moveLocation(lat: Double = latitude, lon: Double = longitude, n: Double, angle: Double = bearing): Pair<Double, Double> {
        require(n.isFinite() && n >= 0 && angle.isFinite())
        if (n == 0.0) return lat to lon
        val distance = n / 6371000.0
        val latitudeRadians = Math.toRadians(lat)
        val direction = Math.toRadians(angle)
        val newLat = asin((sin(latitudeRadians) * cos(distance) + cos(latitudeRadians) * sin(distance) * cos(direction)).coerceIn(-1.0, 1.0))
        val newLon = Math.toRadians(lon) + atan2(sin(direction) * sin(distance) * cos(latitudeRadians), cos(distance) - sin(latitudeRadians) * sin(newLat))
        return Math.toDegrees(newLat) to ((Math.toDegrees(newLon) + 540) % 360 - 180)
    }

    fun calculateBearing(latA: Double, lonA: Double, latB: Double, lonB: Double): Double {
        val lat1 = Math.toRadians(latA)
        val lon1 = Math.toRadians(lonA)
        val lat2 = Math.toRadians(latB)
        val lon2 = Math.toRadians(lonB)

        val deltaLon = lon2 - lon1

        val y = sin(deltaLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon)

        var bearing = Math.toDegrees(atan2(y, x))
        bearing = (bearing + 360) % 360  // 标准化到0-360度

        return bearing
    }
}

