package com.system.location.service.core

import com.system.location.service.core.environment.BleBeacon
import com.system.location.service.core.environment.CellMode
import com.system.location.service.core.environment.CellModePolicy
import com.system.location.service.core.environment.CellTower
import com.system.location.service.core.environment.CellType
import com.system.location.service.core.environment.CellSignalPolicy
import com.system.location.service.core.environment.CdmaTower
import com.system.location.service.core.environment.EnvironmentConfig
import com.system.location.service.core.environment.EnvironmentProfile
import com.system.location.service.core.environment.EnvironmentProfileCodec
import com.system.location.service.core.environment.EnvironmentSourceType
import com.system.location.service.core.environment.ProceduralBleSource
import com.system.location.service.core.environment.WifiAp
import com.system.location.service.core.geo.Wgs84
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun sampleAp(connected: Boolean = false) = WifiAp(
        ssid = "Portal-Test",
        bssid = "A4:50:46:12:34:56",
        rssi = -52,
        frequencyMhz = 2412,
        channel = 1,
        connected = connected,
    )

    @Test fun apRangeMatchesCommandSchemaRuntimeAndGenerator() {
        val schema = com.system.location.service.core.settings.PortalSettingsSchema.schema
        for (count in 0..10) {
            if (count in EnvironmentConfig.MIN_AP_PER_CELL..EnvironmentConfig.MAX_AP_PER_CELL) {
                org.junit.Assert.assertNull(schema.validate("environment_ap_count", count.toString()))
                org.junit.Assert.assertEquals(count, com.system.location.service.core.runtime.RuntimeSnapshot(environmentApCount = count).environmentApCount)
                org.junit.Assert.assertEquals(count, EnvironmentConfig(apPerCell = count).apPerCell)
            } else {
                org.junit.Assert.assertNotNull(schema.validate("environment_ap_count", count.toString()))
                assertThrows { com.system.location.service.core.runtime.RuntimeSnapshot(environmentApCount = count) }
                assertThrows { EnvironmentConfig(apPerCell = count) }
            }
        }
    }

    @Test
    fun profileRoundTripsThroughJson() {
        val profile = EnvironmentProfile(
            source = EnvironmentSourceType.PROCEDURAL,
            origin = Wgs84(31.2304, 121.4737),
            generatedAtMillis = 1_790_000_000_000,
            seed = 42,
            wifi = listOf(sampleAp(connected = true)),
            cells = listOf(
                CellTower(
                    type = CellType.LTE,
                    mcc = 460,
                    mnc = 0,
                    areaCode = 1234,
                    cellId = 56789L,
                    pci = 120,
                    rsrp = -85,
                    registered = true,
                ),
            ),
            ble = listOf(BleBeacon("C4:50:46:12:34:56", "Beacon", -70, scanRecordHex = "ff")),
        )

        assertEquals(profile, json.decodeFromString(EnvironmentProfile.serializer(), json.encodeToString(EnvironmentProfile.serializer(), profile)))
        assertEquals(profile, EnvironmentProfileCodec.decode(EnvironmentProfileCodec.encode(profile)))
        assertEquals("Portal-Test", profile.connectedWifi()?.ssid)
        assertEquals(120, profile.registeredCell()?.pci)
    }

    @Test
    fun configRejectsOutOfRangeAccessPointCount() {
        assertThrows { EnvironmentConfig(apPerCell = 2) }
        assertThrows { EnvironmentConfig(apPerCell = 9) }
        assertEquals(CellMode.AUTO, EnvironmentConfig().cellMode)
    }

    @Test
    fun wifiApValidationRejectsSentinelAndInvalidRanges() {
        assertThrows { sampleAp().copy(bssid = "02:00:00:00:00:00") }
        assertThrows { sampleAp().copy(ssid = "") }
        assertThrows { sampleAp().copy(ssid = "中".repeat(11)) }
        assertThrows { sampleAp().copy(rssi = 1) }
        assertThrows { sampleAp().copy(rssi = WifiAp.MIN_RSSI - 1) }
        assertThrows { sampleAp().copy(frequencyMhz = 100) }
        assertThrows { sampleAp().copy(channel = 0) }
        assertThrows { sampleAp().copy(standard = WifiAp.MAX_STANDARD + 1) }
        assertThrows { sampleAp().copy(standard = WifiAp.MIN_STANDARD - 1) }
    }

    @Test fun wifiSsidLengthUsesUtf8Bytes() {
        val maxAscii = "a".repeat(WifiAp.MAX_SSID_BYTES)
        val maxMultibyte = "中".repeat(10) + "a" + "b"
        assertEquals(WifiAp.MAX_SSID_BYTES, maxAscii.toByteArray(Charsets.UTF_8).size)
        assertEquals(WifiAp.MAX_SSID_BYTES, maxMultibyte.toByteArray(Charsets.UTF_8).size)
        assertEquals(maxAscii, sampleAp().copy(ssid = maxAscii).ssid)
        assertEquals(maxMultibyte, sampleAp().copy(ssid = maxMultibyte).ssid)
    }

    @Test fun wifiFrequencyMapsToCorrectBandChannelAndRejectsUnknownCenters() {
        assertEquals(1, WifiAp.channelForFrequencyMhz(2412))
        assertEquals(14, WifiAp.channelForFrequencyMhz(2484))
        assertEquals(182, WifiAp.channelForFrequencyMhz(4910))
        assertEquals(36, WifiAp.channelForFrequencyMhz(5180))
        assertEquals(2, WifiAp.channelForFrequencyMhz(5935))
        assertEquals(1, WifiAp.channelForFrequencyMhz(5955))
        assertEquals(5, WifiAp.channelForFrequencyMhz(5975))
        assertEquals(233, WifiAp.channelForFrequencyMhz(7115))
        assertNull(WifiAp.channelForFrequencyMhz(2413))
        assertNull(WifiAp.channelForFrequencyMhz(5940))
        assertNull(WifiAp.channelForFrequencyMhz(7125))
    }

    @Test
    fun cellValidationRejectsInvalidIdentity() {
        val cell = CellTower(
            type = CellType.NR,
            mcc = 460,
            mnc = 0,
            areaCode = 1,
            cellId = 1L,
            pci = 1,
            rsrp = -90,
        )
        assertThrows { cell.copy(mcc = 0) }
        assertThrows { cell.copy(cellId = 0) }
        assertThrows { cell.copy(rsrp = 0) }
        assertThrows { cell.copy(pci = 1008) }
    }

    @Test
    fun cellOperatorIdentityPreservesMncWidthAndRejectsInvalidWidth() {
        val threeDigitMnc = CellTower(
            type = CellType.LTE,
            mcc = 310,
            mnc = 1,
            areaCode = 1,
            cellId = 1,
            rsrp = -90,
            mncDigits = 3,
            operatorName = "Example Carrier",
        )

        assertEquals("310001", threeDigitMnc.operatorNumeric)
        assertThrows { threeDigitMnc.copy(mnc = 100, mncDigits = 2) }
        assertThrows { threeDigitMnc.copy(operatorName = " ") }
        assertThrows { threeDigitMnc.copy(operatorName = "x".repeat(CellTower.MAX_OPERATOR_NAME_BYTES + 1)) }
        assertEquals(null, threeDigitMnc.copy(mncDigits = null).operatorNumeric)
    }

    @Test
    fun cellValidationUsesRadioTechnologySpecificRanges() {
        val gsm = CellTower(CellType.GSM, 460, 0, 65_535, 65_535, pci = 63, arfcn = 1_023, rsrp = -113)
        val wcdma = CellTower(CellType.WCDMA, 460, 0, 65_535, 268_435_455, pci = 511, arfcn = 16_383, rsrp = -120)
        assertEquals(CellType.GSM, gsm.type)
        assertEquals(CellType.WCDMA, wcdma.type)
        assertThrows { gsm.copy(cellId = 65_536) }
        assertThrows { gsm.copy(pci = 64) }
        assertThrows { gsm.copy(rsrp = -114) }
        assertThrows { wcdma.copy(pci = 512) }
        assertThrows { wcdma.copy(arfcn = 16_384) }
        assertThrows { wcdma.copy(areaCode = 65_536) }
        assertThrows { wcdma.copy(rsrp = -121) }
        assertEquals(null, CellSignalPolicy.lte(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(null, CellSignalPolicy.nr(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(-90, CellSignalPolicy.lte(-90, Int.MAX_VALUE, Int.MAX_VALUE)?.rsrp)
        assertEquals(null, CellSignalPolicy.lte(-90, Int.MAX_VALUE, Int.MAX_VALUE)?.rsrq)
        assertEquals(null, CellSignalPolicy.nr(-90, Int.MAX_VALUE, Int.MAX_VALUE)?.rssnr)
        assertEquals(null, CellSignalPolicy.lte(-141, -10, 5))
        assertEquals(null, CellSignalPolicy.nr(-157, -10, 5))
    }

    @Test
    fun cdmaTowerValidatesIdentityCoordinatesAndSignals() {
        val tower = CdmaTower(
            systemId = 42,
            networkId = 513,
            baseStationId = 1234,
            latitudeQuarterSeconds = 450_000,
            longitudeQuarterSeconds = -1_750_000,
            cdmaDbm = -92,
            cdmaEcio = -10,
            evdoDbm = -98,
            evdoEcio = -12,
            evdoSnr = 5,
            registered = true,
        )

        assertEquals(42, tower.systemId)
        assertThrows { tower.copy(systemId = CdmaTower.MAX_SYSTEM_ID + 1) }
        assertThrows { tower.copy(networkId = CdmaTower.MAX_NETWORK_ID + 1) }
        assertThrows { tower.copy(latitudeQuarterSeconds = null) }
        assertThrows { tower.copy(longitudeQuarterSeconds = CdmaTower.MAX_LONGITUDE_QUARTER_SECONDS + 1) }
        assertThrows { tower.copy(cdmaDbm = 0) }
        assertThrows { tower.copy(evdoSnr = CdmaTower.MAX_EVDO_SNR + 1) }
    }

    @Test
    fun bleBeaconBoundsRawAdvertisementAndTransmitPower() {
        val beacon = BleBeacon("C4:50:46:12:34:56", "Beacon", -70,
            scanRecordHex = "020106", txPowerDbm = -12)
        assertEquals("020106", beacon.scanRecordHex)
        assertEquals(-12, beacon.txPowerDbm)
        assertThrows { beacon.copy(scanRecordHex = "xyz") }
        assertFalse(BleBeacon.isValidAdvertisementHex("０２０１０６"))
        assertFalse(BleBeacon.isValidAdvertisementHex("ff"))
        assertFalse(BleBeacon.isValidAdvertisementHex("020106020a"))
        assertTrue(BleBeacon.isValidAdvertisementHex("020106020af9"))
        assertTrue(BleBeacon.isValidAdvertisementHex("02010600"))
        assertEquals("ff", beacon.copy(scanRecordHex = "ff").scanRecordHex)
        assertThrows { beacon.copy(scanRecordHex = "ff".repeat(BleBeacon.MAX_SCAN_RECORD_BYTES + 1)) }
        assertThrows { beacon.copy(rssi = -101) }
        assertThrows { beacon.copy(txPowerDbm = 127) }
        assertThrows { beacon.copy(name = "中".repeat(BleBeacon.MAX_NAME_BYTES / 3 + 1)) }
        val maxUtf8Name = "中".repeat(82) + "a" + "b"
        assertEquals(BleBeacon.MAX_NAME_BYTES, maxUtf8Name.toByteArray(Charsets.UTF_8).size)
        assertEquals(maxUtf8Name, beacon.copy(name = maxUtf8Name).name)
    }

    @Test
    fun proceduralBleSourceIsStableAndProducesValidAdvertisements() {
        val point = Wgs84(31.2304, 121.4737)
        val source = ProceduralBleSource()
        val first = source.beaconsAt(point, 42L)

        assertEquals(first, source.beaconsAt(point, 42L))
        assertEquals(6, first.size)
        first.forEach { beacon ->
            assertTrue(beacon.address.startsWith("02:"))
            assertTrue(beacon.scanRecordHex!!.contains("020106"))
            assertEquals(-59, beacon.txPowerDbm)
        }
        assertFalse(first == source.beaconsAt(Wgs84(31.2314, 121.4737), 42L))
    }

    @Test
    fun profileRejectsMultipleConnectedOrRegisteredEntries() {
        val base = EnvironmentProfile(
            source = EnvironmentSourceType.PROCEDURAL,
            generatedAtMillis = 1,
            seed = 1,
            wifi = listOf(sampleAp(connected = true)),
        )
        assertThrows { base.copy(wifi = base.wifi + sampleAp(connected = true)) }
        assertThrows {
            base.copy(
                cells = listOf(CellTower(CellType.LTE, 460, 0, 1, 1, rsrp = -90, registered = true)),
                cdmaCells = listOf(CdmaTower(1, 1, 1, registered = true)),
            )
        }
        assertEquals(null, base.registeredCell())
        assertNull(base.origin)
    }

    @Test
    fun profileBoundsReplayPayloadSize() {
        val ap = sampleAp()
        val tooMany = List(EnvironmentProfile.MAX_WIFI + 1) { ap }
        assertThrows {
            EnvironmentProfile(
                source = EnvironmentSourceType.REPLAY,
                generatedAtMillis = 1,
                seed = 1,
                wifi = tooMany,
            )
        }
    }

    @Test
    fun autoCellModeFollowsSimStateAndLegacyIsExplicit() {
        assertEquals(CellMode.FULL_REPLAY, CellModePolicy.resolve(CellMode.AUTO, simReady = true))
        assertEquals(CellMode.NO_SIM_WIFI_ONLY, CellModePolicy.resolve(CellMode.AUTO, simReady = false))
        assertEquals(CellMode.FULL_REPLAY, CellModePolicy.resolve(CellMode.FULL_REPLAY, simReady = false))
        assertTrue(CellModePolicy.isLegacyCdma(CellMode.LEGACY_CDMA))
        assertFalse(CellModePolicy.isLegacyCdma(CellMode.AUTO))
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected IllegalArgumentException")
    }
}
