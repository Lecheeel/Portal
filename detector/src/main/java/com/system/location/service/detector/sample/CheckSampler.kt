package com.system.location.service.detector.sample

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Looper
import android.telephony.TelephonyManager
import com.system.location.service.detector.DetectorSettings
import com.system.location.service.detector.model.GnssFrameSnapshot
import com.system.location.service.detector.model.GnssSatelliteSnapshot
import com.system.location.service.detector.model.NmeaSentenceSnapshot
import com.system.location.service.detector.model.SampleSnapshot
import com.system.location.service.detector.model.SensorSeries
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Collects the evidence snapshot used by every check. Blocking by design: the caller runs it on a
 * worker thread and only the UI is updated on the main thread.
 *
 * A denied permission is recorded in [SampleSnapshot.notes] instead of failing the whole run; the
 * affected checks then report UNKNOWN with the reason.
 */
@SuppressLint("MissingPermission")
class CheckSampler(
    private val context: Context,
    private val settings: DetectorSettings,
) {
    fun collect(onTick: (remainingSeconds: Int) -> Unit): SampleSnapshot {
        val startedAt = System.currentTimeMillis()
        val notes = Collections.synchronizedList(mutableListOf<String>())
        val locations = Collections.synchronizedList(mutableListOf<Location>())
        val gnssFrames = Collections.synchronizedList(mutableListOf<GnssFrameSnapshot>())
        val nmeaSentences = Collections.synchronizedList(mutableListOf<NmeaSentenceSnapshot>())
        val sensorSamples = ConcurrentHashMap<Int, MutableList<FloatArray>>()
        val lastKnown = LinkedHashMap<String, Location>()

        val locationManager = context.getSystemService(LocationManager::class.java)
        val executor = context.mainExecutor
        val sensorManager = context.getSystemService(SensorManager::class.java)
        val wifiManager = context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
        val telephonyManager = context.getSystemService(TelephonyManager::class.java)

        val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (locations.size < MAX_LOCATIONS) locations.add(Location(location))
            }
        }
        val gnssCallback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                if (gnssFrames.size >= MAX_GNSS_FRAMES) return
                val satellites = ArrayList<GnssSatelliteSnapshot>(status.satelliteCount)
                for (index in 0 until status.satelliteCount) {
                    satellites += GnssSatelliteSnapshot(
                        constellation = status.getConstellationType(index),
                        svid = status.getSvid(index),
                        cn0DbHz = status.getCn0DbHz(index),
                        elevationDegrees = status.getElevationDegrees(index),
                        azimuthDegrees = status.getAzimuthDegrees(index),
                        usedInFix = status.usedInFix(index),
                        hasEphemeris = status.hasEphemerisData(index),
                        hasAlmanac = status.hasAlmanacData(index),
                        hasCarrierFrequency = status.hasCarrierFrequencyHz(index),
                    )
                }
                gnssFrames.add(GnssFrameSnapshot(System.currentTimeMillis(), satellites))
            }
        }
        val nmeaListener = OnNmeaMessageListener { message: String?, timestamp: Long ->
            if (message != null && nmeaSentences.size < MAX_NMEA) {
                nmeaSentences.add(NmeaSentenceSnapshot(timestamp, message))
            }
        }
        val sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val series = sensorSamples.getOrPut(event.sensor.type) { Collections.synchronizedList(mutableListOf()) }
                if (series.size < MAX_SENSOR_SAMPLES) series.add(event.values.copyOf())
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        var registeredGnss = false
        var registeredNmea = false
        var registeredSensors = 0
        try {
            if (locationManager == null) {
                notes += "LocationManager 不可用"
            } else {
                val providers = linkedSetOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                runCatching { locationManager.allProviders }.getOrDefault(emptyList())
                    .filter { it == "fused" }
                    .forEach { providers += it }
                providers.forEach { provider ->
                    runCatching {
                        locationManager.requestLocationUpdates(provider, 500L, 0f, locationListener, Looper.getMainLooper())
                    }.onFailure {
                        notes += "$provider requestLocationUpdates 失败：${it.javaClass.simpleName}"
                    }
                    runCatching { locationManager.getLastKnownLocation(provider) }
                        .getOrNull()?.let { lastKnown[provider] = Location(it) }
                }
                runCatching { locationManager.registerGnssStatusCallback(executor, gnssCallback) }
                    .onSuccess { registeredGnss = true }
                    .onFailure { notes += "GnssStatus 注册失败：${it.javaClass.simpleName}" }
                runCatching { locationManager.addNmeaListener(executor, nmeaListener) }
                    .onSuccess { registeredNmea = true }
                    .onFailure { notes += "NMEA 注册失败：${it.javaClass.simpleName}" }
            }

            if (sensorManager == null) {
                notes += "SensorManager 不可用"
            } else {
                SENSOR_TYPES.forEach { type ->
                    val sensor = sensorManager.getDefaultSensor(type) ?: return@forEach
                    runCatching { sensorManager.registerListener(sensorListener, sensor, SensorManager.SENSOR_DELAY_NORMAL) }
                        .onSuccess { registeredSensors++ }
                        .onFailure { notes += "传感器 $type 注册失败：${it.javaClass.simpleName}" }
                }
            }

            wifiManager?.let { manager ->
                runCatching { manager.startScan() }.onFailure {
                    notes += "WiFi startScan 失败：${it.javaClass.simpleName}"
                }
            }

            val durationSeconds = settings.sampleSeconds
            for (remaining in durationSeconds downTo 1) {
                onTick(remaining)
                Thread.sleep(1_000L)
            }
        } finally {
            locationManager?.let { manager ->
                runCatching { manager.removeUpdates(locationListener) }
                if (registeredGnss) runCatching { manager.unregisterGnssStatusCallback(gnssCallback) }
                if (registeredNmea) runCatching { manager.removeNmeaListener(nmeaListener) }
            }
            if (sensorManager != null && registeredSensors > 0) runCatching { sensorManager.unregisterListener(sensorListener) }
        }

        val scanResults = runCatching { wifiManager?.scanResults.orEmpty() }.getOrDefault(emptyList())
        val wifiInfo = runCatching { wifiManager?.connectionInfo }.getOrNull()
        val dhcpInfo = runCatching { wifiManager?.dhcpInfo }.getOrNull()
        val wifiEnabled = runCatching { wifiManager?.isWifiEnabled == true }.getOrDefault(false)
        val cellInfo = runCatching { telephonyManager?.allCellInfo.orEmpty() }.getOrDefault(emptyList())

        return SampleSnapshot(
            startedAtMillis = startedAt,
            endedAtMillis = System.currentTimeMillis(),
            locations = locations.toList(),
            gnssFrames = gnssFrames.toList(),
            nmeaSentences = nmeaSentences.toList(),
            availability = emptyList(),
            sensorSeries = sensorSamples.mapValues { (type, values) -> SensorSeries(type, values.toList()) },
            scanResults = scanResults,
            wifiInfo = wifiInfo,
            dhcpInfo = dhcpInfo,
            cellInfo = cellInfo,
            wifiEnabled = wifiEnabled,
            lastKnownByProvider = lastKnown,
            notes = notes.toList(),
        )
    }

    companion object {
        private const val MAX_LOCATIONS = 2_000
        private const val MAX_GNSS_FRAMES = 300
        private const val MAX_NMEA = 2_000
        private const val MAX_SENSOR_SAMPLES = 500
        private val SENSOR_TYPES = listOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_PRESSURE,
            Sensor.TYPE_STEP_COUNTER,
            Sensor.TYPE_STEP_DETECTOR,
        )
    }
}
