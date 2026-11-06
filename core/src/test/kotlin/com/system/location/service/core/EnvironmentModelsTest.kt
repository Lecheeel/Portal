package com.system.location.service.core

import com.system.location.service.core.environment.BleBeacon
import com.system.location.service.core.environment.CellMode
import com.system.location.service.core.environment.CellTower
import com.system.location.service.core.environment.CellType
import com.system.location.service.core.environment.EnvironmentConfig
import com.system.location.service.core.environment.EnvironmentProfile
import com.system.location.service.core.environment.EnvironmentSourceType
import com.system.location.service.core.environment.WifiAp
import com.system.location.service.core.geo.Wgs84
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
            ble = listOf(BleBeacon("C4:50:46:12:34:56", "Beacon", -70)),
        )

        assertEquals(profile, json.decodeFromString(EnvironmentProfile.serializer(), json.encodeToString(EnvironmentProfile.serializer(), profile)))
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
        assertThrows { sampleAp().copy(rssi = 1) }
        assertThrows { sampleAp().copy(frequencyMhz = 100) }
        assertThrows { sampleAp().copy(channel = 0) }
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
    fun profileRejectsMultipleConnectedOrRegisteredEntries() {
        val base = EnvironmentProfile(
            source = EnvironmentSourceType.PROCEDURAL,
            generatedAtMillis = 1,
            seed = 1,
            wifi = listOf(sampleAp(connected = true)),
        )
        assertThrows { base.copy(wifi = base.wifi + sampleAp(connected = true)) }
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

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected IllegalArgumentException")
    }
}
