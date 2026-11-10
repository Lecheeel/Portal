package com.system.location.service.hook.utils

import android.location.Location
import com.system.location.service.core.environment.EnvironmentConfig
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import android.os.SystemClock
import kotlin.math.asin
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.runtime.RuntimeSnapshot
import com.system.location.service.core.runtime.RuntimeSnapshotStore

object FakeLoc {
    /**
     * 是否允许打印日志
     */
    var enableLog: Boolean
        get() = runtimeSnapshots.current().configuration.logEnabled
        set(value) { updateSnapshot { it.copy(configuration = it.configuration.copy(logEnabled = value)) } }

    /** Authoritative immutable sample/environment/routing/policy state; properties below are adapters. */
    val runtimeSnapshots = RuntimeSnapshotStore()

    private fun updateSnapshot(transform: (RuntimeSnapshot) -> RuntimeSnapshot) {
        runtimeSnapshots.update { transform(it).copy(configRevision = it.configRevision + 1) }
    }

    /**
     * 是否允许打印调试日志
     */
    var enableDebugLog: Boolean
        get() = runtimeSnapshots.current().configuration.debugLog
        set(value) { updateSnapshot { it.copy(configuration = it.configuration.copy(debugLog = value)) } }

    /**
     * 模拟定位服务开关
     */
    var enable: Boolean
        get() = runtimeSnapshots.current().configuration.enabled
        set(value) { updateSnapshot { it.copy(configuration = it.configuration.copy(enabled = value)) } }

    /**
     * 模拟Gnss卫星数据开关（默认开启，仅在模拟定位启动时生效）
     */
    var enableMockGnss: Boolean
        get() = runtimeSnapshots.current().configuration.gnssEnabled
        set(value) { updateSnapshot { it.copy(configuration = it.configuration.copy(gnssEnabled = value)) } }

    /**
     * 模拟WLAN数据（默认开启，仅在模拟定位启动时生效）
     */
    var enableMockWifi: Boolean
        get() = runtimeSnapshots.current().configuration.wifiEnabled
        set(value) { updateSnapshot { it.copy(configuration = it.configuration.copy(wifiEnabled = value)) } }

    /**
     * 是否禁用GetCurrentLocation方法（在部分系统不禁用可能导致hook失效）
     */
    var disableGetCurrentLocation: Boolean
        get() = runtimeSnapshots.current().configuration.disableGetCurrentLocation
        set(value) { updateSnapshot { it.copy(configuration = it.configuration.copy(disableGetCurrentLocation = value)) } }

    /**
     * 是否禁用RegisterLocationListener方法
     */
    var disableRegisterLocationListener: Boolean
        get() = runtimeSnapshots.current().configuration.disableRegisterLocationListener
        set(value) { updateSnapshot { it.copy(configuration = it.configuration.copy(disableRegisterLocationListener = value)) } }

    /**
     * 如果TelephonyHook失效，可能需要打开此开关
     */
    var disableFusedLocation: Boolean
        get() = runtimeSnapshots.current().configuration.disableFusedLocation
        set(value) { updateSnapshot { it.copy(configuration = it.configuration.copy(disableFusedLocation = value)) } }
    var disableNetworkLocation: Boolean
        get() = runtimeSnapshots.current().configuration.disableNetworkLocation
        set(value) { updateSnapshot { it.copy(configuration = it.configuration.copy(disableNetworkLocation = value)) } }

    var disableRequestGeofence: Boolean
        get() = runtimeSnapshots.current().configuration.disableRequestGeofence
        set(value) { updateSnapshot { it.copy(configuration = it.configuration.copy(disableRequestGeofence = value)) } }
    var disableGetFromLocation: Boolean
        get() = runtimeSnapshots.current().configuration.disableGetFromLocation
        set(value) { updateSnapshot { it.copy(configuration = it.configuration.copy(disableGetFromLocation = value)) } }

    /** Legacy enable_agps key now controls only real raw GNSS data access. */
    var allowPhysicalRawGnss: Boolean
        get() = runtimeSnapshots.current().configuration.allowPhysicalRawGnss
        set(value) { updateSnapshot { it.copy(configuration = it.configuration.copy(allowPhysicalRawGnss = value)) } }

    /**
     * 是否允许NMEA模块
     */
    var enableNMEA: Boolean
        get() = runtimeSnapshots.current().configuration.nmeaEnabled
        set(value) { updateSnapshot { it.copy(configuration = it.configuration.copy(nmeaEnabled = value)) } }

    /**
     * 是否隐藏模拟位置
     */
    var hideMock: Boolean
        get() = runtimeSnapshots.current().configuration.hideMock
        set(value) { updateSnapshot { it.copy(configuration = it.configuration.copy(hideMock = value)) } }

    /**
     * may cause system to crash
     */
    var hookWifi: Boolean
        get() = runtimeSnapshots.current().configuration.hookWifi
        set(value) { updateSnapshot { it.copy(configuration = it.configuration.copy(hookWifi = value)) } }

    /**
     * 将网络定位降级为 Cdma（ADR-005：旧路径默认关闭，下一版本删除）
     */
    var needDowngradeToCdma: Boolean
        get() = runtimeSnapshots.current().configuration.downgradeToCdma
        set(value) { updateSnapshot { it.copy(configuration = it.configuration.copy(downgradeToCdma = value)) } }
    var isSystemServerProcess = false

    /**
     * 模拟最小卫星数量
     */
    var minSatellites: Int
        get() = runtimeSnapshots.current().configuration.minimumSatellites
        set(value) { updateSnapshot { it.copy(configuration = it.configuration.copy(minimumSatellites = value)) } }

    /** 环境生成 seed；同一位置、同一 seed 必须生成同一组 AP/基站（ADR-022）。 */
    var environmentSeed: Long
        get() = runtimeSnapshots.current().environmentSeed
        set(value) { updateSnapshot { it.copy(environmentSeed = value) } }

    var replayEnvironment: com.system.location.service.core.environment.EnvironmentProfile?
        get() = runtimeSnapshots.current().environment
        set(value) {
            runtimeSnapshots.update {
                it.copy(environment = value, environmentVersion = it.environmentVersion + 1,
                    configRevision = it.configRevision + 1)
            }
        }

    /** Optional bounded OU drift; disabled by default and never applied to real locations. */
    var enablePhysicalNoise: Boolean
        get() = runtimeSnapshots.current().physicalNoise
        set(value) { updateSnapshot { it.copy(physicalNoise = value) } }
    var physicalNoiseSeed: Long
        get() = runtimeSnapshots.current().physicalNoiseSeed
        set(value) { updateSnapshot { it.copy(physicalNoiseSeed = value) } }

    /** P2-05: false means NO_SIM_WIFI_ONLY; default true preserves real/current behavior. */
    var simReady: Boolean
        get() = runtimeSnapshots.current().simReady
        set(value) { updateSnapshot { it.copy(simReady = value) } }

    /** P2-07 environment knobs; defaults preserve the P2-03/P2-05 behavior. */
    var environmentApCount: Int
        get() = runtimeSnapshots.current().environmentApCount
        set(value) { updateSnapshot { it.copy(environmentApCount = value.coerceIn(EnvironmentConfig.MIN_AP_PER_CELL, EnvironmentConfig.MAX_AP_PER_CELL)) } }
    var cellMode: com.system.location.service.core.environment.CellMode
        get() = runtimeSnapshots.current().cellMode
        set(value) { updateSnapshot { it.copy(cellMode = value) } }
    var environmentSourceMode: com.system.location.service.core.environment.EnvironmentSourceMode
        get() = runtimeSnapshots.current().environmentSourceMode
        set(value) { updateSnapshot { it.copy(environmentSourceMode = value) } }

    /** P2-06: optional imported WiFi fingerprint catalog (JSON); disabled by default. */
    var wifiFingerprintEnabled: Boolean
        get() = runtimeSnapshots.current().configuration.wifiFingerprintEnabled
        set(value) { updateSnapshot { it.copy(configuration = it.configuration.copy(wifiFingerprintEnabled = value)) } }
    var wifiFingerprintCatalogJson: String?
        get() = runtimeSnapshots.current().configuration.wifiFingerprintCatalogJson
        set(value) { updateSnapshot { it.copy(configuration = it.configuration.copy(wifiFingerprintCatalogJson = value)) } }

    /** P5-02: null/global keeps every listener on the current fake fix. */
    var routingConfig: com.system.location.service.core.routing.RoutingConfig?
        get() = runtimeSnapshots.current().routing
        set(value) { updateSnapshot { it.copy(routing = value) } }

    /**
     * 上一次的位置
     */
    @Volatile var lastLocation: Location? = null
    val latitude get() = runtimeSnapshots.current().latitude
    val longitude get() = runtimeSnapshots.current().longitude
    fun coordinatePair(): Pair<Double, Double> = runtimeSnapshots.current().let { it.latitude to it.longitude }
    var altitude: Double
        get() = runtimeSnapshots.current().altitudeMeters
        set(value) { runtimeSnapshots.update { it.copy(altitudeMeters = value) } }

    var speed: Double
        get() = runtimeSnapshots.current().speedMps
        set(value) { runtimeSnapshots.update { it.copy(speedMps = value.coerceAtLeast(0.0)) } }

    var speedAmplitude: Double
        get() = runtimeSnapshots.current().configuration.speedAmplitude
        set(value) { updateSnapshot { it.copy(configuration = it.configuration.copy(speedAmplitude = value)) } }

    var hasBearings: Boolean
        get() = runtimeSnapshots.current().hasBearings
        set(value) { runtimeSnapshots.update { it.copy(hasBearings = value) } }

    var bearing: Double
        get() = runtimeSnapshots.current().bearingDeg
        set(value) { runtimeSnapshots.update { it.copy(bearingDeg = value) } }
    var reportIntervalMs: Long
        get() = runtimeSnapshots.current().configuration.reportIntervalMs
        set(value) { updateSnapshot { it.copy(configuration = it.configuration.copy(reportIntervalMs = value)) } }

    /** Java-side sensor tuning; Native only consumes the resulting cadence/variation values. */
    var sensorCadenceScale: Double
        get() = runtimeSnapshots.current().configuration.sensorCadenceScale
        set(value) { updateSnapshot { it.copy(configuration = it.configuration.copy(sensorCadenceScale = value)) } }
    var sensorVariation: Double
        get() = runtimeSnapshots.current().configuration.sensorVariation
        set(value) { updateSnapshot { it.copy(configuration = it.configuration.copy(sensorVariation = value)) } }
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
        runtimeSnapshots.update { it.copy(latitude = lat, longitude = lon, sample = null, provider = null) }
        movementSpeed = movingSpeed.coerceAtLeast(0f)
        lastMovementNanos = nowNanos
        externallyDriven = false
    }

    @Synchronized
    fun acceptSample(fix: LocationSample) {
        runtimeSnapshots.update {
            it.copy(
                latitude = fix.latitude,
                longitude = fix.longitude,
                altitudeMeters = fix.altitude,
                accuracyMeters = fix.accuracy.coerceAtLeast(0.1f),
                speedMps = fix.speed.toDouble().coerceAtLeast(0.0),
                bearingDeg = fix.bearing.toDouble(),
                hasBearings = true,
                sample = fix,
            )
        }
        externallyDriven = true
    }

    @Synchronized
    fun snapshot(force: Boolean = false, now: Long = SystemClock.elapsedRealtimeNanos(), timeMillis: Long = System.currentTimeMillis()): LocationSample {
        runtimeSnapshots.current().sample?.let {
            if (externallyDriven) return it
            if (!force && now - it.elapsedNanos < reportIntervalMs.coerceIn(50, 1000) * 1_000_000) return it
        }
        val point = coordinatePair()
        lastSampleNanos = maxOf(now, lastSampleNanos + 1)
        return LocationSample(
            Wgs84(point.first, point.second), altitude, accuracy.coerceAtLeast(0.1f),
            if (now - lastMovementNanos <= maxOf(500L, reportIntervalMs * 2) * 1_000_000) movementSpeed else 0f,
            ((bearing % 360 + 360) % 360).toFloat().coerceAtMost(359.99997f),
            timeMillis, lastSampleNanos,
        ).also { runtimeSnapshots.publishSample(it) }
    }

    var accuracy: Float
        get() = runtimeSnapshots.current().accuracyMeters
        set(value) {
            runtimeSnapshots.update { it.copy(accuracyMeters = kotlin.math.abs(value).coerceAtLeast(0.1f)) }
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

