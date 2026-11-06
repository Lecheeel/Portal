package com.system.location.service.environment

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import com.system.location.service.core.environment.CellTower
import com.system.location.service.core.environment.CellType
import com.system.location.service.core.environment.EnvironmentProfile
import com.system.location.service.core.environment.EnvironmentSourceType
import com.system.location.service.core.environment.WifiAp
import com.system.location.service.core.geo.Wgs84

/**
 * P2-02 capture: public-API WiFi/cell snapshot for later replay.
 *
 * BLE capture needs the Bluetooth scan runtime flow and is left empty for now. Every source is
 * best-effort: a denied permission or a ROM-specific SecurityException returns an empty list instead
 * of failing the capture.
 */
class EnvironmentCollector(private val context: Context) {

    fun capture(
        origin: Wgs84?,
        seed: Long = 0L,
        atMillis: Long = System.currentTimeMillis(),
    ): EnvironmentProfile = EnvironmentProfile(
        source = EnvironmentSourceType.REPLAY,
        origin = origin,
        generatedAtMillis = atMillis,
        seed = seed,
        wifi = captureWifi(),
        cells = captureCells(),
    )

    @Suppress("DEPRECATION", "MissingPermission")
    private fun captureWifi(): List<WifiAp> {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val manager = context.applicationContext.getSystemService(WifiManager::class.java) ?: return emptyList()
        val connectedBssid = runCatching { manager.connectionInfo?.bssid?.lowercase() }.getOrNull()
        val results = runCatching { manager.scanResults }.getOrDefault(emptyList())
        return results.mapNotNull { result ->
            runCatching {
                val ssid = result.SSID?.takeIf { it.isNotBlank() && !it.contains("unknown ssid") } ?: return@runCatching null
                val bssid = result.BSSID?.takeIf { WifiAp.BSSID_PATTERN.matches(it) } ?: return@runCatching null
                if (bssid.equals(WifiAp.SENTINEL_BSSID, ignoreCase = true)) return@runCatching null
                val frequency = result.frequency.takeIf { it in WifiAp.MIN_FREQUENCY_MHZ..WifiAp.MAX_FREQUENCY_MHZ } ?: 2412
                WifiAp(
                    ssid = ssid.take(WifiAp.MAX_SSID_LENGTH),
                    bssid = bssid,
                    rssi = result.level.coerceIn(WifiAp.MIN_RSSI, WifiAp.MAX_RSSI),
                    frequencyMhz = frequency,
                    channel = frequencyToChannel(frequency),
                    capabilities = result.capabilities ?: "[ESS]",
                    connected = bssid.lowercase() == connectedBssid,
                )
            }.getOrNull()
        }.distinctBy { it.bssid.lowercase() }.take(EnvironmentProfile.MAX_WIFI)
    }

    @Suppress("MissingPermission")
    private fun captureCells(): List<CellTower> {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val manager = context.getSystemService(TelephonyManager::class.java) ?: return emptyList()
        val cells = runCatching { manager.allCellInfo }.getOrDefault(emptyList())
        return cells.mapNotNull { cell -> runCatching { cell.toTower() }.getOrNull() }
            .distinctBy { "${it.mcc}:${it.mnc}:${it.areaCode}:${it.cellId}" }
            .take(EnvironmentProfile.MAX_CELLS)
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
        is CellInfoGsm -> cellIdentity.toTower(cellSignalStrength.dbm, null, null, CellType.GSM, isRegistered)
        is CellInfoWcdma -> cellIdentity.toTower(cellSignalStrength.dbm, null, null, CellType.WCDMA, isRegistered)
        else -> null
    }

    private fun CellIdentityLte.toTower(rsrp: Int, rsrq: Int, rssnr: Int, registered: Boolean): CellTower? {
        if (mcc !in 1..999 || mnc !in 0..999 || ci <= 0) return null
        return CellTower(
            type = CellType.LTE,
            mcc = mcc,
            mnc = mnc,
            areaCode = tac.coerceAtLeast(0),
            cellId = ci.toLong(),
            pci = pci.takeIf { it in 0..1007 },
            arfcn = earfcn.takeIf { it > 0 },
            rsrp = rsrp.coerceIn(CellTower.MIN_RSRP, CellTower.MAX_RSRP),
            rsrq = rsrq,
            rssnr = rssnr,
            registered = registered,
        )
    }

    private fun CellIdentityNr.toTower(rsrp: Int, rsrq: Int, rssnr: Int, registered: Boolean): CellTower? {
        val mcc = mccString?.toIntOrNull() ?: return null
        val mnc = mncString?.toIntOrNull() ?: return null
        if (mcc !in 1..999 || mnc !in 0..999 || nci <= 0) return null
        return CellTower(
            type = CellType.NR,
            mcc = mcc,
            mnc = mnc,
            areaCode = tac.coerceAtLeast(0),
            cellId = nci,
            pci = pci.takeIf { it in 0..1007 },
            arfcn = nrarfcn.takeIf { it > 0 },
            rsrp = rsrp.coerceIn(CellTower.MIN_RSRP, CellTower.MAX_RSRP),
            rsrq = rsrq,
            rssnr = rssnr,
            registered = registered,
        )
    }

    private fun android.telephony.CellIdentityGsm.toTower(
        dbm: Int,
        rsrq: Int?,
        rssnr: Int?,
        type: CellType,
        registered: Boolean,
    ): CellTower? {
        if (mcc !in 1..999 || mnc !in 0..999 || cid <= 0) return null
        return CellTower(
            type = type,
            mcc = mcc,
            mnc = mnc,
            areaCode = lac.coerceAtLeast(0),
            cellId = cid.toLong(),
            rsrp = dbm.coerceIn(CellTower.MIN_RSRP, CellTower.MAX_RSRP),
            rsrq = rsrq,
            rssnr = rssnr,
            registered = registered,
        )
    }

    private fun android.telephony.CellIdentityWcdma.toTower(
        dbm: Int,
        rsrq: Int?,
        rssnr: Int?,
        type: CellType,
        registered: Boolean,
    ): CellTower? {
        if (mcc !in 1..999 || mnc !in 0..999 || cid <= 0) return null
        return CellTower(
            type = type,
            mcc = mcc,
            mnc = mnc,
            areaCode = lac.coerceAtLeast(0),
            cellId = cid.toLong(),
            pci = psc.takeIf { it in 0..1007 },
            rsrp = dbm.coerceIn(CellTower.MIN_RSRP, CellTower.MAX_RSRP),
            rsrq = rsrq,
            rssnr = rssnr,
            registered = registered,
        )
    }

    private fun frequencyToChannel(frequencyMhz: Int): Int = when (frequencyMhz) {
        2484 -> 14
        in 2_400..2_500 -> (frequencyMhz - 2_407) / 5
        in 5_000..6_000 -> (frequencyMhz - 5_000) / 5
        in 5_925..7_125 -> (frequencyMhz - 5_950) / 5 + 1
        else -> 1
    }
}
