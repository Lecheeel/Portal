package com.system.location.service.core.environment

import com.system.location.service.core.geo.Wgs84
import kotlinx.serialization.Serializable

/** Where the slow environment (WiFi/cell/BLE) comes from. */
enum class EnvironmentSourceType { PROCEDURAL, REPLAY, DATABASE }

/** Cell profile selected by [EnvironmentConfig.cellMode]; AUTO resolves at runtime. */
enum class CellMode { AUTO, FULL_REPLAY, NO_SIM_WIFI_ONLY, LEGACY_CDMA }

/** Pure AUTO resolution shared by the hook and future collection/replay tooling (P2-05). */
object CellModePolicy {
    fun resolve(configured: CellMode, simReady: Boolean): CellMode = when (configured) {
        CellMode.AUTO -> if (simReady) CellMode.FULL_REPLAY else CellMode.NO_SIM_WIFI_ONLY
        else -> configured
    }

    fun isLegacyCdma(mode: CellMode): Boolean = mode == CellMode.LEGACY_CDMA
}

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
        require(ssid.isNotBlank() && ssid.toByteArray(Charsets.UTF_8).size <= MAX_SSID_BYTES) {
            "SSID must contain 1..$MAX_SSID_BYTES UTF-8 bytes"
        }
        require(BSSID_PATTERN.matches(bssid)) { "Invalid BSSID: $bssid" }
        require(!bssid.equals(SENTINEL_BSSID, ignoreCase = true)) { "Sentinel BSSID is not a valid AP" }
        require(rssi in MIN_RSSI..MAX_RSSI) { "RSSI out of range: $rssi" }
        require(frequencyMhz in MIN_FREQUENCY_MHZ..MAX_FREQUENCY_MHZ) {
            "Frequency out of range: $frequencyMhz"
        }
        require(channel in MIN_CHANNEL..MAX_CHANNEL) { "Channel out of range: $channel" }
        require(standard in MIN_STANDARD..MAX_STANDARD) { "Wi-Fi standard out of range: $standard" }
    }

    companion object {
        const val MAX_SSID_BYTES = 32
        const val MIN_RSSI = -100
        const val MAX_RSSI = 0
        const val MIN_FREQUENCY_MHZ = 2_400
        const val MAX_FREQUENCY_MHZ = 7_125
        const val MIN_CHANNEL = 1
        const val MAX_CHANNEL = 233
        const val MIN_STANDARD = 0
        const val MAX_STANDARD = 8
        const val SENTINEL_BSSID = "02:00:00:00:00:00"
        val BSSID_PATTERN = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")

        fun channelForFrequencyMhz(frequencyMhz: Int): Int? = when {
            frequencyMhz == 2484 -> 14
            frequencyMhz in 2412..2472 && (frequencyMhz - 2407) % 5 == 0 ->
                (frequencyMhz - 2407) / 5
            frequencyMhz in 4910..4980 && (frequencyMhz - 4000) % 5 == 0 ->
                (frequencyMhz - 4000) / 5
            frequencyMhz in 5005..5895 && (frequencyMhz - 5000) % 5 == 0 ->
                (frequencyMhz - 5000) / 5
            frequencyMhz == 5935 -> 2
            frequencyMhz in 5955..7115 && (frequencyMhz - 5950) % 20 == 5 ->
                (frequencyMhz - 5950) / 5
            else -> null
        }
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
    val mncDigits: Int? = null,
    val operatorName: String? = null,
    val bands: List<Int> = emptyList(),
) {
    init {
        require(bands.size <= 16 && bands.all { it in 1..1024 } && bands.distinct().size == bands.size) {
            "Invalid cell bands"
        }
        require(mcc in 1..999) { "mcc out of range: $mcc" }
        require(mnc in 0..999) { "mnc out of range: $mnc" }
        mncDigits?.let {
            require(it in MIN_MNC_DIGITS..MAX_MNC_DIGITS) { "mncDigits out of range: $it" }
            require(mnc < powerOfTen(it)) { "mnc does not fit mncDigits: $mnc / $it" }
        }
        operatorName?.let {
            require(it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= MAX_OPERATOR_NAME_BYTES) {
                "operatorName must contain 1..$MAX_OPERATOR_NAME_BYTES UTF-8 bytes"
            }
        }
        require(areaCode >= 0) { "areaCode must be non-negative" }
        val maxCellId = when (type) {
            CellType.GSM -> MAX_GSM_CID
            CellType.WCDMA, CellType.LTE -> MAX_LTE_CID
            CellType.NR -> MAX_NR_NCI
        }
        require(cellId in 1..maxCellId) { "cellId out of range for $type: $cellId" }
        val maxAreaCode = if (type == CellType.GSM || type == CellType.WCDMA) MAX_LAC else MAX_TAC
        require(areaCode <= maxAreaCode) { "areaCode out of range for $type: $areaCode" }
        val signalRange = when (type) {
            CellType.GSM -> MIN_GSM_RSSI..MAX_GSM_RSSI
            CellType.WCDMA -> MIN_WCDMA_RSCP..MAX_WCDMA_RSCP
            CellType.LTE, CellType.NR -> MIN_RSRP..MAX_RSRP
        }
        require(rsrp in signalRange) { "signal out of range for $type: $rsrp" }
        pci?.let {
            val maxPci = when (type) {
                CellType.GSM -> MAX_GSM_BSIC
                CellType.WCDMA -> MAX_WCDMA_PSC
                CellType.LTE -> MAX_LTE_PCI
                CellType.NR -> MAX_NR_PCI
            }
            require(it in 0..maxPci) { "pci out of range for $type: $it" }
        }
        arfcn?.let {
            val maxArfcn = when (type) {
                CellType.GSM -> MAX_GSM_ARFCN
                CellType.WCDMA -> MAX_WCDMA_UARFCN
                CellType.LTE -> MAX_LTE_EARFCN
                CellType.NR -> MAX_NR_ARFCN
            }
            require(it in 0..maxArfcn) { "arfcn out of range for $type: $it" }
        }
    }

    val operatorNumeric: String?
        get() = mncDigits?.let { "${mcc.toString().padStart(3, '0')}${mnc.toString().padStart(it, '0')}" }

    private fun powerOfTen(digits: Int): Int = when (digits) {
        2 -> 100
        3 -> 1_000
        else -> 0
    }

    companion object {
        const val MIN_MNC_DIGITS = 2
        const val MAX_MNC_DIGITS = 3
        const val MAX_OPERATOR_NAME_BYTES = 128
        const val MAX_GSM_CID = 65_535L
        const val MAX_LTE_CID = 268_435_455L
        const val MAX_NR_NCI = 68_719_476_735L
        const val MAX_LAC = 65_535
        const val MAX_TAC = 16_777_215
        const val MIN_GSM_RSSI = -113
        const val MAX_GSM_RSSI = -51
        const val MIN_WCDMA_RSCP = -120
        const val MAX_WCDMA_RSCP = -25
        const val MAX_GSM_BSIC = 63
        const val MAX_WCDMA_PSC = 511
        const val MAX_LTE_PCI = 503
        const val MAX_NR_PCI = 1_007
        const val MAX_GSM_ARFCN = 1_023
        const val MAX_WCDMA_UARFCN = 16_383
        const val MAX_LTE_EARFCN = 262_143
        const val MAX_NR_ARFCN = 3_279_165
        const val MIN_RSRP = -156
        const val MAX_RSRP = -31
        const val MIN_LTE_RSRP = -140
        const val MAX_LTE_RSRP = -43
        const val MIN_NR_RSRP = -156
        const val MAX_NR_RSRP = -31
        const val MIN_LTE_RSRQ = -20
        const val MAX_LTE_RSRQ = -3
        const val MIN_NR_RSRQ = -43
        const val MAX_NR_RSRQ = 20
        const val MIN_LTE_RSSNR = -20
        const val MAX_LTE_RSSNR = 30
        const val MIN_NR_SINR = -23
        const val MAX_NR_SINR = 40
    }
}

data class CellSignalMetrics(val rsrp: Int, val rsrq: Int?, val rssnr: Int?)

/** Converts platform radio measurements without turning unavailable sentinels into plausible values. */
object CellSignalPolicy {
    private const val UNAVAILABLE = Int.MAX_VALUE

    fun lte(rsrp: Int, rsrq: Int, rssnr: Int): CellSignalMetrics? {
        if (rsrp !in CellTower.MIN_LTE_RSRP..CellTower.MAX_LTE_RSRP) return null
        return CellSignalMetrics(
            rsrp = rsrp,
            rsrq = rsrq.takeIf { it != UNAVAILABLE && it in CellTower.MIN_LTE_RSRQ..CellTower.MAX_LTE_RSRQ },
            rssnr = rssnr.takeIf { it != UNAVAILABLE && it in CellTower.MIN_LTE_RSSNR..CellTower.MAX_LTE_RSSNR },
        )
    }

    fun nr(rsrp: Int, rsrq: Int, sinr: Int): CellSignalMetrics? {
        if (rsrp !in CellTower.MIN_NR_RSRP..CellTower.MAX_NR_RSRP) return null
        return CellSignalMetrics(
            rsrp = rsrp,
            rsrq = rsrq.takeIf { it != UNAVAILABLE && it in CellTower.MIN_NR_RSRQ..CellTower.MAX_NR_RSRQ },
            rssnr = sinr.takeIf { it != UNAVAILABLE && it in CellTower.MIN_NR_SINR..CellTower.MAX_NR_SINR },
        )
    }
}

/** CDMA uses SID/NID/BID identity and does not share the MCC/MNC/LAC/CID fields above. */
@Serializable
data class CdmaTower(
    val systemId: Int,
    val networkId: Int,
    val baseStationId: Int,
    val latitudeQuarterSeconds: Int? = null,
    val longitudeQuarterSeconds: Int? = null,
    val cdmaDbm: Int? = null,
    val cdmaEcio: Int? = null,
    val evdoDbm: Int? = null,
    val evdoEcio: Int? = null,
    val evdoSnr: Int? = null,
    val registered: Boolean = false,
) {
    init {
        require(systemId in MIN_SYSTEM_ID..MAX_SYSTEM_ID) { "systemId out of range: $systemId" }
        require(networkId in MIN_NETWORK_ID..MAX_NETWORK_ID) { "networkId out of range: $networkId" }
        require(baseStationId in MIN_BASE_STATION_ID..MAX_BASE_STATION_ID) {
            "baseStationId out of range: $baseStationId"
        }
        require((latitudeQuarterSeconds == null) == (longitudeQuarterSeconds == null)) {
            "CDMA latitude and longitude must be set together"
        }
        latitudeQuarterSeconds?.let { require(it in MIN_LATITUDE_QUARTER_SECONDS..MAX_LATITUDE_QUARTER_SECONDS) }
        longitudeQuarterSeconds?.let { require(it in MIN_LONGITUDE_QUARTER_SECONDS..MAX_LONGITUDE_QUARTER_SECONDS) }
        cdmaDbm?.let { require(it in MIN_SIGNAL_DBM..MAX_SIGNAL_DBM) }
        cdmaEcio?.let { require(it in MIN_ECIO_DB..MAX_ECIO_DB) }
        evdoDbm?.let { require(it in MIN_SIGNAL_DBM..MAX_SIGNAL_DBM) }
        evdoEcio?.let { require(it in MIN_ECIO_DB..MAX_ECIO_DB) }
        evdoSnr?.let { require(it in MIN_EVDO_SNR..MAX_EVDO_SNR) }
    }

    companion object {
        const val MIN_SYSTEM_ID = 0
        const val MAX_SYSTEM_ID = 32_767
        const val MIN_NETWORK_ID = 0
        const val MAX_NETWORK_ID = 65_535
        const val MIN_BASE_STATION_ID = 0
        const val MAX_BASE_STATION_ID = 65_535
        const val MIN_LATITUDE_QUARTER_SECONDS = -1_296_000
        const val MAX_LATITUDE_QUARTER_SECONDS = 1_296_000
        const val MIN_LONGITUDE_QUARTER_SECONDS = -2_592_000
        const val MAX_LONGITUDE_QUARTER_SECONDS = 2_592_000
        const val MIN_SIGNAL_DBM = -160
        const val MAX_SIGNAL_DBM = -1
        const val MIN_ECIO_DB = -160
        const val MAX_ECIO_DB = 0
        const val MIN_EVDO_SNR = 0
        const val MAX_EVDO_SNR = 8
    }
}

@Serializable
data class BleBeacon(
    val address: String,
    val name: String,
    val rssi: Int,
    val scanRecordHex: String? = null,
    val txPowerDbm: Int? = null,
) {
    init {
        require(BSSID_PATTERN.matches(address)) { "Invalid BLE address: $address" }
        require(name.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES) {
            "BLE name exceeds $MAX_NAME_BYTES UTF-8 bytes"
        }
        require(rssi in -100..0) { "BLE RSSI out of range: $rssi" }
        scanRecordHex?.let {
            require(it.length % 2 == 0 && it.length <= MAX_SCAN_RECORD_BYTES * 2 &&
                it.all { c -> c in '0'..'9' || c.lowercaseChar() in 'a'..'f' }) {
                "Invalid BLE advertisement hex"
            }
        }
        txPowerDbm?.let { require(it in MIN_TX_POWER_DBM..MAX_TX_POWER_DBM) }
    }

    companion object {
        const val MAX_NAME_BYTES = 248
        const val MAX_SCAN_RECORD_BYTES = 1_650
        const val MIN_TX_POWER_DBM = -127
        const val MAX_TX_POWER_DBM = 126
        private val BSSID_PATTERN = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")

        fun isValidAdvertisementHex(hex: String): Boolean {
            if (hex.isEmpty() || hex.length % 2 != 0 || hex.length > MAX_SCAN_RECORD_BYTES * 2 ||
                !hex.all { c -> c in '0'..'9' || c.lowercaseChar() in 'a'..'f' }) return false
            val bytes = hex.chunked(2).map { it.toInt(16) }
            var offset = 0
            var structures = 0
            while (offset < bytes.size) {
                val length = bytes[offset]
                if (length == 0) return structures > 0
                if (length > bytes.size - offset - 1) return false
                offset += length + 1
                structures++
            }
            return structures > 0
        }
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
    val originAccuracyMeters: Double? = null,
    val generatedAtMillis: Long,
    val seed: Long,
    val wifi: List<WifiAp> = emptyList(),
    val cells: List<CellTower> = emptyList(),
    val cdmaCells: List<CdmaTower> = emptyList(),
    val ble: List<BleBeacon> = emptyList(),
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported environment schemaVersion: $schemaVersion" }
        require(generatedAtMillis >= 0L) { "generatedAtMillis must be non-negative" }
        require(originAccuracyMeters == null || origin != null) { "origin accuracy requires an origin" }
        originAccuracyMeters?.let { require(it.isFinite() && it > 0.0) { "originAccuracyMeters must be positive and finite" } }
        require(wifi.size <= MAX_WIFI) { "Too many WiFi APs: ${wifi.size}" }
        require(cells.size + cdmaCells.size <= MAX_CELLS) { "Too many cells: ${cells.size + cdmaCells.size}" }
        require(ble.size <= MAX_BLE) { "Too many BLE beacons: ${ble.size}" }
        require(wifi.count { it.connected } <= 1) { "At most one connected AP is allowed" }
        require(cells.count { it.registered } + cdmaCells.count { it.registered } <= 1) {
            "At most one registered cell is allowed"
        }
    }

    fun connectedWifi(): WifiAp? = wifi.firstOrNull { it.connected }

    fun registeredCell(): CellTower? = cells.firstOrNull { it.registered }

    fun registeredCdmaCell(): CdmaTower? = cdmaCells.firstOrNull { it.registered }

    companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_WIFI = 256
        const val MAX_CELLS = 128
        const val MAX_BLE = 256
    }
}

/** Deterministic contract for procedural/replay/database sources (ADR-022). */
interface EnvironmentSource {
    fun profileAt(point: Wgs84, seed: Long, atMillis: Long): EnvironmentProfile
}
