package com.system.location.service.core.environment

import com.system.location.service.core.geo.Wgs84
import kotlinx.serialization.Serializable

/** Where the slow environment (WiFi/cell/BLE) comes from. */
enum class EnvironmentSourceType { PROCEDURAL, REPLAY, DATABASE }

/** Cell profile selected by [EnvironmentConfig.cellMode]; AUTO resolves at runtime. */
enum class CellMode { AUTO, FULL_REPLAY, NO_SIM_WIFI_ONLY, LEGACY_CDMA }

enum class CellType { GSM, WCDMA, LTE, NR }

@Serializable
data class EnvironmentConfig(
    val source: EnvironmentSourceType = EnvironmentSourceType.PROCEDURAL,
    val apPerCell: Int = 4,
    val cellMode: CellMode = CellMode.AUTO,
    val enableBle: Boolean = false,
) {
    init {
        require(apPerCell in MIN_AP_PER_CELL..MAX_AP_PER_CELL) {
            "apPerCell must be in $MIN_AP_PER_CELL..$MAX_AP_PER_CELL"
        }
    }

    companion object {
        const val MIN_AP_PER_CELL = 3
        const val MAX_AP_PER_CELL = 8
    }
}

@Serializable
data class WifiAp(
    val ssid: String,
    val bssid: String,
    val rssi: Int,
    val frequencyMhz: Int,
    val channel: Int,
    val capabilities: String = "[WPA2-PSK-CCMP][ESS]",
    val standard: Int = 4,
    val connected: Boolean = false,
) {
    init {
        require(ssid.isNotBlank() && ssid.length <= MAX_SSID_LENGTH) { "Invalid SSID" }
        require(BSSID_PATTERN.matches(bssid)) { "Invalid BSSID: $bssid" }
        require(!bssid.equals(SENTINEL_BSSID, ignoreCase = true)) { "Sentinel BSSID is not a valid AP" }
        require(rssi in MIN_RSSI..MAX_RSSI) { "RSSI out of range: $rssi" }
        require(frequencyMhz in MIN_FREQUENCY_MHZ..MAX_FREQUENCY_MHZ) {
            "Frequency out of range: $frequencyMhz"
        }
        require(channel in MIN_CHANNEL..MAX_CHANNEL) { "Channel out of range: $channel" }
    }

    companion object {
        const val MAX_SSID_LENGTH = 32
        const val MIN_RSSI = -100
        const val MAX_RSSI = 0
        const val MIN_FREQUENCY_MHZ = 2_400
        const val MAX_FREQUENCY_MHZ = 7_125
        const val MIN_CHANNEL = 1
        const val MAX_CHANNEL = 233
        const val SENTINEL_BSSID = "02:00:00:00:00:00"
        val BSSID_PATTERN = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
    }
}

@Serializable
data class CellTower(
    val type: CellType,
    val mcc: Int,
    val mnc: Int,
    val areaCode: Int,
    val cellId: Long,
    val pci: Int? = null,
    val arfcn: Int? = null,
    val rsrp: Int,
    val rsrq: Int? = null,
    val rssnr: Int? = null,
    val registered: Boolean = false,
) {
    init {
        require(mcc in 1..999) { "mcc out of range: $mcc" }
        require(mnc in 0..999) { "mnc out of range: $mnc" }
        require(areaCode >= 0) { "areaCode must be non-negative" }
        require(cellId > 0) { "cellId must be positive" }
        require(rsrp in MIN_RSRP..MAX_RSRP) { "rsrp out of range: $rsrp" }
        pci?.let { require(it in 0..1007) { "pci out of range: $it" } }
    }

    companion object {
        const val MIN_RSRP = -156
        const val MAX_RSRP = -31
    }
}

@Serializable
data class BleBeacon(
    val address: String,
    val name: String,
    val rssi: Int,
    val scanRecordHex: String? = null,
) {
    init {
        require(BSSID_PATTERN.matches(address)) { "Invalid BLE address: $address" }
        require(rssi in -100..0) { "BLE RSSI out of range: $rssi" }
        scanRecordHex?.let { require(it.length % 2 == 0 && it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' }) }
    }

    companion object {
        private val BSSID_PATTERN = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
    }
}

/**
 * Slow-variable environment snapshot; GNSS is a fast variable and is modeled separately (P3-01).
 * Bounded lists keep an imported/replayed profile from becoming an unbounded IPC payload.
 */
@Serializable
data class EnvironmentProfile(
    val source: EnvironmentSourceType,
    val origin: Wgs84? = null,
    val generatedAtMillis: Long,
    val seed: Long,
    val wifi: List<WifiAp> = emptyList(),
    val cells: List<CellTower> = emptyList(),
    val ble: List<BleBeacon> = emptyList(),
) {
    init {
        require(generatedAtMillis >= 0L) { "generatedAtMillis must be non-negative" }
        require(wifi.size <= MAX_WIFI) { "Too many WiFi APs: ${wifi.size}" }
        require(cells.size <= MAX_CELLS) { "Too many cells: ${cells.size}" }
        require(ble.size <= MAX_BLE) { "Too many BLE beacons: ${ble.size}" }
        require(wifi.count { it.connected } <= 1) { "At most one connected AP is allowed" }
        require(cells.count { it.registered } <= 1) { "At most one registered cell is allowed" }
    }

    fun connectedWifi(): WifiAp? = wifi.firstOrNull { it.connected }

    fun registeredCell(): CellTower? = cells.firstOrNull { it.registered }

    companion object {
        const val MAX_WIFI = 256
        const val MAX_CELLS = 128
        const val MAX_BLE = 256
    }
}

/** Deterministic contract for procedural/replay/database sources (ADR-022). */
interface EnvironmentSource {
    fun profileAt(point: Wgs84, seed: Long, atMillis: Long): EnvironmentProfile
}
