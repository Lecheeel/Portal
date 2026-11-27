package com.system.location.service.environment

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult as BleScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.ScanResult as WifiScanResult
import android.os.Build
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentity
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.system.location.service.core.environment.BleBeacon
import com.system.location.service.core.environment.CellTower
import com.system.location.service.core.environment.CellType
import com.system.location.service.core.environment.CellSignalPolicy
import com.system.location.service.core.environment.CdmaTower
import com.system.location.service.core.environment.EnvironmentProfile
import com.system.location.service.core.environment.EnvironmentSourceType
import com.system.location.service.core.environment.WifiAp
import com.system.location.service.core.geo.Wgs84
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Captures a public-API WiFi/cell/BLE snapshot for a user-managed environment profile. Every source
 * is best-effort: denied permission or a ROM-specific SecurityException does not fail the capture.
 */
class EnvironmentCollector(private val context: Context) {

    suspend fun capture(
        origin: Wgs84? = null,
        originAccuracyMeters: Double? = null,
        seed: Long = 0L,
        atMillis: Long = System.currentTimeMillis(),
        originElapsedRealtimeNanos: Long? = null,
        onProgress: (String) -> Unit = {},
    ): EnvironmentProfile {
        onProgress("正在读取蜂窝小区…")
        val cells = captureCells(originElapsedRealtimeNanos)
        onProgress("正在扫描 Wi-Fi…")
        val wifi = captureWifi(originElapsedRealtimeNanos)
        onProgress("正在扫描蓝牙信标…")
        val ble = captureBle(originElapsedRealtimeNanos)
        return EnvironmentProfile(
            source = EnvironmentSourceType.REPLAY,
            origin = origin,
            originAccuracyMeters = originAccuracyMeters,
            generatedAtMillis = atMillis,
            seed = seed,
            wifi = wifi,
            cells = cells.gsm,
            cdmaCells = cells.cdma,
            ble = ble,
        )
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private suspend fun captureWifi(originElapsedRealtimeNanos: Long?): List<WifiAp> {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val manager = context.applicationContext.getSystemService(WifiManager::class.java) ?: return emptyList()
        val connectedBssid = runCatching { manager.connectionInfo?.bssid?.lowercase() }.getOrNull()
        val results = requestWifiScanResults(manager)
        return results.asSequence().filter { result ->
            originElapsedRealtimeNanos == null || EnvironmentCaptureFreshness.wifiTimestampNanos(result.timestamp)
                ?.let { EnvironmentCaptureFreshness.matchesFix(it, originElapsedRealtimeNanos) } == true
        }.mapNotNull { result ->
            runCatching {
                val ssid = result.SSID?.takeIf { it.isNotBlank() && !it.contains("unknown ssid") } ?: return@runCatching null
                val bssid = result.BSSID?.takeIf { WifiAp.BSSID_PATTERN.matches(it) } ?: return@runCatching null
                if (bssid.equals(WifiAp.SENTINEL_BSSID, ignoreCase = true)) return@runCatching null
                val frequency = result.frequency
                val channel = WifiAp.channelForFrequencyMhz(frequency) ?: return@runCatching null
                val capabilities = result.capabilities ?: return@runCatching null
                WifiAp(
                    ssid = ssid,
                    bssid = bssid,
                    rssi = result.level,
                    frequencyMhz = frequency,
                    channel = channel,
                    capabilities = capabilities,
                    standard = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) result.wifiStandard else WifiAp.MIN_STANDARD,
                    connected = bssid.lowercase() == connectedBssid,
                )
            }.getOrNull()
        }.distinctBy { it.bssid.lowercase() }.take(EnvironmentProfile.MAX_WIFI).toList()
    }

    @Suppress("MissingPermission")
    private data class CellCapture(val gsm: List<CellTower>, val cdma: List<CdmaTower>)

    @Suppress("DEPRECATION")
    private suspend fun captureCells(originElapsedRealtimeNanos: Long?): CellCapture {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return CellCapture(emptyList(), emptyList())
        }
        val manager = context.getSystemService(TelephonyManager::class.java)
            ?: return CellCapture(emptyList(), emptyList())
        val updated = requestCellInfoUpdate(manager)
        val source = updated?.takeIf { it.isNotEmpty() }
            ?: runCatching { manager.allCellInfo }.getOrDefault(emptyList())
        val cells = source.filter { cell ->
            if (originElapsedRealtimeNanos == null) return@filter true
            val timestampNanos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                EnvironmentCaptureFreshness.cellTimestampNanos(cell.timestampMillis)
            } else {
                cell.timeStamp.takeUnless { it == Long.MAX_VALUE }
            }
            timestampNanos?.let { EnvironmentCaptureFreshness.matchesFix(it, originElapsedRealtimeNanos) } == true
        }
        val gsm = cells.mapNotNull { cell -> runCatching { cell.toTower() }.getOrNull() }
            .distinctBy { "${it.mcc}:${it.mnc}:${it.areaCode}:${it.cellId}" }
            .take(EnvironmentProfile.MAX_CELLS)
        val cdma = cells.mapNotNull { cell -> runCatching { cell.toCdmaTower() }.getOrNull() }
            .distinctBy { "${it.systemId}:${it.networkId}:${it.baseStationId}" }
            .take((EnvironmentProfile.MAX_CELLS - gsm.size).coerceAtLeast(0))
        return CellCapture(gsm, cdma)
    }

    @Suppress("MissingPermission")
    private suspend fun requestCellInfoUpdate(manager: TelephonyManager): List<CellInfo>? =
        withTimeoutOrNull(CELL_INFO_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                val completed = AtomicBoolean(false)
                try {
                    manager.requestCellInfoUpdate(context.mainExecutor, object : TelephonyManager.CellInfoCallback() {
                        override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                            if (completed.compareAndSet(false, true) && continuation.isActive) {
                                continuation.resume(cellInfo.toList())
                            }
                        }

                        override fun onError(errorCode: Int, detail: Throwable?) {
                            if (completed.compareAndSet(false, true) && continuation.isActive) {
                                continuation.resume(null)
                            }
                        }
                    })
                } catch (_: Exception) {
                    if (completed.compareAndSet(false, true) && continuation.isActive) continuation.resume(null)
                }
            }
        }

    @Suppress("MissingPermission", "DEPRECATION")
    private suspend fun requestWifiScanResults(manager: WifiManager): List<WifiScanResult> {
        fun cached() = runCatching { manager.scanResults }.getOrDefault(emptyList())
        return withTimeoutOrNull(WIFI_SCAN_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                val completed = AtomicBoolean(false)
                val registered = AtomicBoolean(false)
                lateinit var receiver: BroadcastReceiver
                fun unregister() {
                    if (registered.compareAndSet(true, false)) runCatching { context.unregisterReceiver(receiver) }
                }
                fun finish(results: List<WifiScanResult>) {
                    if (completed.compareAndSet(false, true)) {
                        unregister()
                        if (continuation.isActive) continuation.resume(results)
                    }
                }
                receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) finish(cached())
                    }
                }
                try {
                    registered.set(true)
                    ContextCompat.registerReceiver(
                        context, receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                        ContextCompat.RECEIVER_EXPORTED,
                    )
                    continuation.invokeOnCancellation { completed.set(true); unregister() }
                    if (continuation.isActive && !completed.get() &&
                        !runCatching { manager.startScan() }.getOrDefault(false)
                    ) finish(cached())
                } catch (_: Exception) {
                    registered.set(false)
                    finish(cached())
                }
            }
        } ?: cached()
    }

    private fun CellInfo.toTower(): CellTower? = when (this) {
        is CellInfoLte -> (cellSignalStrength as? android.telephony.CellSignalStrengthLte)?.let { signal ->
            cellIdentity.toTower(signal.rsrp, signal.rsrq, signal.rssnr, isRegistered)
        }
        is CellInfoNr -> {
            val identity = cellIdentity as? CellIdentityNr
            val signal = cellSignalStrength as? android.telephony.CellSignalStrengthNr
            if (identity == null || signal == null) null else identity.toTower(
                signal.ssRsrp, signal.ssRsrq, signal.ssSinr, isRegistered,
            )
        }
        is CellInfoGsm -> cellIdentity.toTower(
            cellSignalStrength.dbm, cellIdentity.bsic, cellIdentity.arfcn,
            CellType.GSM, isRegistered,
        )
        is CellInfoWcdma -> cellIdentity.toTower(
            cellSignalStrength.dbm, cellIdentity.psc, cellIdentity.uarfcn,
            CellType.WCDMA, isRegistered,
        )
        else -> null
    }

    private fun CellInfo.toCdmaTower(): CdmaTower? {
        val cell = this as? CellInfoCdma ?: return null
        val identity = cell.cellIdentity
        val signal = cell.cellSignalStrength
        val latitude = identity.latitude.takeIf {
            it in CdmaTower.MIN_LATITUDE_QUARTER_SECONDS..CdmaTower.MAX_LATITUDE_QUARTER_SECONDS
        }
        val longitude = identity.longitude.takeIf {
            it in CdmaTower.MIN_LONGITUDE_QUARTER_SECONDS..CdmaTower.MAX_LONGITUDE_QUARTER_SECONDS
        }
        val hasCoordinates = latitude != null && longitude != null
        return CdmaTower(
            systemId = identity.systemId,
            networkId = identity.networkId,
            baseStationId = identity.basestationId,
            latitudeQuarterSeconds = latitude.takeIf { hasCoordinates },
            longitudeQuarterSeconds = longitude.takeIf { hasCoordinates },
            cdmaDbm = signal.cdmaDbm.takeIf { it in CdmaTower.MIN_SIGNAL_DBM..CdmaTower.MAX_SIGNAL_DBM },
            cdmaEcio = signal.cdmaEcio.takeIf { it in CdmaTower.MIN_ECIO_DB..CdmaTower.MAX_ECIO_DB },
            evdoDbm = signal.evdoDbm.takeIf { it in CdmaTower.MIN_SIGNAL_DBM..CdmaTower.MAX_SIGNAL_DBM },
            evdoEcio = signal.evdoEcio.takeIf { it in CdmaTower.MIN_ECIO_DB..CdmaTower.MAX_ECIO_DB },
            evdoSnr = signal.evdoSnr.takeIf { it in CdmaTower.MIN_EVDO_SNR..CdmaTower.MAX_EVDO_SNR },
            registered = cell.isRegistered,
        )
    }

    private fun CellIdentityLte.toTower(rsrp: Int, rsrq: Int, rssnr: Int, registered: Boolean): CellTower? {
        if (mcc !in 1..999 || mnc !in 0..999 || ci <= 0) return null
        val signal = CellSignalPolicy.lte(rsrp, rsrq, rssnr) ?: return null
        return CellTower(
            type = CellType.LTE,
            mcc = mcc,
            mnc = mnc,
            areaCode = tac,
            cellId = ci.toLong(),
            pci = pci.takeIf { it in 0..CellTower.MAX_LTE_PCI },
            arfcn = earfcn.takeIf { it > 0 },
            rsrp = signal.rsrp,
            rsrq = signal.rsrq,
            rssnr = signal.rssnr,
            registered = registered,
            mncDigits = mncDigits(mnc),
            operatorName = operatorName(),
        )
    }

    private fun CellIdentityNr.toTower(rsrp: Int, rsrq: Int, rssnr: Int, registered: Boolean): CellTower? {
        val mcc = mccString?.toIntOrNull() ?: return null
        val mnc = mncString?.toIntOrNull() ?: return null
        if (mcc !in 1..999 || mnc !in 0..999 || nci <= 0) return null
        val signal = CellSignalPolicy.nr(rsrp, rsrq, rssnr) ?: return null
        return CellTower(
            type = CellType.NR,
            mcc = mcc,
            mnc = mnc,
            areaCode = tac,
            cellId = nci,
            pci = pci.takeIf { it in 0..CellTower.MAX_NR_PCI },
            arfcn = nrarfcn.takeIf { it > 0 },
            rsrp = signal.rsrp,
            rsrq = signal.rsrq,
            rssnr = signal.rssnr,
            registered = registered,
            mncDigits = mncDigits(mnc),
            operatorName = operatorName(),
        )
    }

    private fun android.telephony.CellIdentityGsm.toTower(
        dbm: Int,
        bsic: Int,
        arfcn: Int,
        type: CellType,
        registered: Boolean,
    ): CellTower? {
        if (mcc !in 1..999 || mnc !in 0..999 || cid <= 0 || dbm !in CellTower.MIN_GSM_RSSI..CellTower.MAX_GSM_RSSI) return null
        return CellTower(
            type = type,
            mcc = mcc,
            mnc = mnc,
            areaCode = lac.coerceAtLeast(0),
            cellId = cid.toLong(),
            pci = bsic.takeIf { it in 0..CellTower.MAX_GSM_BSIC },
            arfcn = arfcn.takeIf { it in 0..CellTower.MAX_GSM_ARFCN },
            rsrp = dbm,
            registered = registered,
            mncDigits = mncDigits(mnc),
            operatorName = operatorName(),
        )
    }

    private fun android.telephony.CellIdentityWcdma.toTower(
        rscp: Int,
        psc: Int,
        uarfcn: Int,
        type: CellType,
        registered: Boolean,
    ): CellTower? {
        if (mcc !in 1..999 || mnc !in 0..999 || cid <= 0 ||
            rscp !in CellTower.MIN_WCDMA_RSCP..CellTower.MAX_WCDMA_RSCP
        ) return null
        return CellTower(
            type = type,
            mcc = mcc,
            mnc = mnc,
            areaCode = lac.coerceAtLeast(0),
            cellId = cid.toLong(),
            pci = psc.takeIf { it in 0..CellTower.MAX_WCDMA_PSC },
            arfcn = uarfcn.takeIf { it in 0..CellTower.MAX_WCDMA_UARFCN },
            rsrp = rscp,
            registered = registered,
            mncDigits = mncDigits(mnc),
            operatorName = operatorName(),
        )
    }

    private fun CellIdentity.mncDigits(expectedMnc: Int): Int? = runCatching {
        (javaClass.getMethod("getMncString").invoke(this) as? String)
            ?.takeIf { it.all(Char::isDigit) && it.toIntOrNull() == expectedMnc }
            ?.length
            ?.takeIf { it in CellTower.MIN_MNC_DIGITS..CellTower.MAX_MNC_DIGITS }
    }.getOrNull()

    private fun CellIdentity.operatorName(): String? = runCatching {
        (javaClass.getMethod("getOperatorAlphaLong").invoke(this) as? String)
            ?.takeIf { it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= CellTower.MAX_OPERATOR_NAME_BYTES }
    }.getOrNull()

    @Suppress("MissingPermission")
    private suspend fun captureBle(originElapsedRealtimeNanos: Long?): List<BleBeacon> {
        val scanPermissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            false
        }
        val fineLocationGranted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!BleScanPermission.isGranted(Build.VERSION.SDK_INT, scanPermissionGranted, fineLocationGranted)) {
            return emptyList()
        }
        val scanner = runCatching {
            context.getSystemService(BluetoothManager::class.java)
                ?.adapter
                ?.takeIf { it.isEnabled }
                ?.bluetoothLeScanner
        }.getOrNull() ?: return emptyList()
        var callback: ScanCallback? = null
        return collectBleScanWindow(
            timeoutMillis = BLE_SCAN_TIMEOUT_MILLIS,
            maxResults = EnvironmentProfile.MAX_BLE,
            startScan = { onResult, onFailure ->
                callback = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: BleScanResult) {
                        if (originElapsedRealtimeNanos != null &&
                            !EnvironmentCaptureFreshness.matchesFix(result.timestampNanos, originElapsedRealtimeNanos)
                        ) return
                        val address = result.device?.address ?: return
                        val beacon = runCatching {
                            val scanRecord = result.scanRecord
                            val scanRecordHex = scanRecord?.bytes
                                ?.takeIf { it.isNotEmpty() && it.size <= BleBeacon.MAX_SCAN_RECORD_BYTES }
                                ?.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
                            val txPower = scanRecord?.txPowerLevel
                                ?.takeIf { it != Int.MIN_VALUE && it in BleBeacon.MIN_TX_POWER_DBM..BleBeacon.MAX_TX_POWER_DBM }
                            BleBeacon(
                                address = address,
                                // The advertised name is available with scan permission. Reading the
                                // device's cached name requires BLUETOOTH_CONNECT on Android 12+.
                                name = scanRecord?.deviceName.orEmpty()
                                    .takeIf { it.toByteArray(Charsets.UTF_8).size <= BleBeacon.MAX_NAME_BYTES }.orEmpty(),
                                rssi = result.rssi,
                                scanRecordHex = scanRecordHex,
                                txPowerDbm = txPower,
                            )
                        }.getOrNull() ?: return
                        onResult(beacon)
                    }

                    override fun onScanFailed(errorCode: Int) = onFailure()
                }
                scanner.startScan(checkNotNull(callback))
            },
            stopScan = { callback?.let { scanner.stopScan(it) } },
        )
    }

    private companion object {
        const val BLE_SCAN_TIMEOUT_MILLIS = 1_500L
        const val WIFI_SCAN_TIMEOUT_MILLIS = 5_000L
        const val CELL_INFO_TIMEOUT_MILLIS = 5_000L
    }
}
