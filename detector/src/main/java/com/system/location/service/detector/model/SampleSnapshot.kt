package com.system.location.service.detector.model

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.net.DhcpInfo
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.telephony.CellInfo
import com.system.location.service.detector.DetectorSettings

/** One satellite snapshot captured from [android.location.GnssStatus]. */
data class GnssSatelliteSnapshot(
    val constellation: Int,
    val svid: Int,
    val cn0DbHz: Float,
    val elevationDegrees: Float,
    val azimuthDegrees: Float,
    val usedInFix: Boolean,
    val hasEphemeris: Boolean,
    val hasAlmanac: Boolean,
    val hasCarrierFrequency: Boolean,
)

data class GnssFrameSnapshot(
    val capturedAtMillis: Long,
    val satellites: List<GnssSatelliteSnapshot>,
)

data class NmeaSentenceSnapshot(
    val capturedAtMillis: Long,
    val sentence: String,
)

data class AvailabilitySnapshot(
    val capturedAtMillis: Long,
    val isAvailable: Boolean,
)

data class SensorSeries(
    val type: Int,
    val values: List<FloatArray>,
)

/**
 * Immutable evidence collected before the checks run.
 *
 * Detector never derives a PASS from its own assumptions: every check reads this snapshot or
 * queries a public Android API at evaluation time.
 */
data class SampleSnapshot(
    val startedAtMillis: Long = 0L,
    val endedAtMillis: Long = 0L,
    val locations: List<Location> = emptyList(),
    val gnssFrames: List<GnssFrameSnapshot> = emptyList(),
    val nmeaSentences: List<NmeaSentenceSnapshot> = emptyList(),
    val availability: List<AvailabilitySnapshot> = emptyList(),
    val sensorSeries: Map<Int, SensorSeries> = emptyMap(),
    val scanResults: List<ScanResult> = emptyList(),
    val wifiInfo: WifiInfo? = null,
    val dhcpInfo: DhcpInfo? = null,
    val cellInfo: List<CellInfo> = emptyList(),
    val wifiEnabled: Boolean = false,
    val lastKnownByProvider: Map<String, Location> = emptyMap(),
    val notes: List<String> = emptyList(),
) {
    val primaryLocation: Location? get() = locations.lastOrNull()

    fun locationsFor(provider: String): List<Location> =
        locations.filter { it.provider == provider }
}

data class CheckContext(
    val context: Context,
    val settings: DetectorSettings,
    val snapshot: SampleSnapshot,
) {
    val locationManager: LocationManager? get() = context.getSystemService(LocationManager::class.java)
}
