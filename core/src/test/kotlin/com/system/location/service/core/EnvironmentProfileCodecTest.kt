package com.system.location.service.core

import com.system.location.service.core.environment.BleBeacon
import com.system.location.service.core.environment.CellTower
import com.system.location.service.core.environment.CellType
import com.system.location.service.core.environment.CdmaTower
import com.system.location.service.core.environment.EnvironmentCodecException
import com.system.location.service.core.environment.EnvironmentProfile
import com.system.location.service.core.environment.EnvironmentProfileCodec
import com.system.location.service.core.environment.EnvironmentSourceType
import com.system.location.service.core.environment.WifiAp
import com.system.location.service.core.geo.Wgs84
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EnvironmentProfileCodecTest {
    private val profile = EnvironmentProfile(
        source = EnvironmentSourceType.REPLAY,
        origin = Wgs84(31.2304, 121.4737),
        originAccuracyMeters = 8.0,
        generatedAtMillis = 1_790_000_000_000,
        seed = 7,
        wifi = listOf(
            WifiAp("ChinaNet", "A4:50:46:12:34:56", -55, 2412, 1, connected = true),
        ),
        cells = listOf(
            CellTower(
                CellType.LTE, 460, 1, 123, 456, pci = 1, rsrp = -90, registered = true,
                mncDigits = 3, operatorName = "Example Carrier",
            ),
        ),
        cdmaCells = listOf(
            CdmaTower(
                systemId = 42,
                networkId = 513,
                baseStationId = 1234,
                latitudeQuarterSeconds = 450_000,
                longitudeQuarterSeconds = -1_750_000,
                cdmaDbm = -92,
                registered = false,
            ),
        ),
        ble = listOf(BleBeacon("C4:50:46:12:34:56", "Beacon", -70, "020106", -12)),
    )

    @Test fun roundTrips() {
        assertEquals(profile, EnvironmentProfileCodec.decode(EnvironmentProfileCodec.encode(profile)))
    }

    @Test fun unknownFieldsAreIgnored() {
        val raw = EnvironmentProfileCodec.encode(profile).trimEnd().removeSuffix("}") + ",\"future\":1}"
        assertEquals(profile, EnvironmentProfileCodec.decode(raw))
    }

    @Test fun legacyProfileWithoutOriginAccuracyRemainsReadableButUnlocated() {
        val legacy = EnvironmentProfileCodec.encode(profile).replace(",\"originAccuracyMeters\":8.0", "")
        assertEquals(null, EnvironmentProfileCodec.decode(legacy).originAccuracyMeters)
    }

    @Test fun legacyBleRecordWithoutTransmitPowerRemainsReadableAsUnknown() {
        val legacy = EnvironmentProfileCodec.encode(profile)
            .replace(",\"txPowerDbm\":-12", "")
        val decoded = EnvironmentProfileCodec.decode(legacy)
        assertEquals(profile.ble.single().scanRecordHex, decoded.ble.single().scanRecordHex)
        assertEquals(null, decoded.ble.single().txPowerDbm)
        assertEquals(profile.wifi, decoded.wifi)
        assertEquals(profile.cells, decoded.cells)
    }

    @Test fun legacyCellRecordsWithoutOperatorMetadataRemainUnknown() {
        val legacy = EnvironmentProfileCodec.encode(profile)
            .replace(",\"mncDigits\":3,\"operatorName\":\"Example Carrier\"", "")

        val cell = EnvironmentProfileCodec.decode(legacy).cells.single()
        assertEquals(null, cell.mncDigits)
        assertEquals(null, cell.operatorName)
        assertEquals(null, cell.operatorNumeric)
    }

    @Test fun legacyProfileWithoutCdmaCellsRemainsReadable() {
        val legacy = EnvironmentProfileCodec.encode(profile)
            .replace(Regex(",\\\"cdmaCells\\\":\\[.*?\\]"), "")
        assertEquals(profile.copy(cdmaCells = emptyList()), EnvironmentProfileCodec.decode(legacy))
    }

    @Test fun invalidJsonAndFutureVersionAreRejected() {
        assertThrows(EnvironmentCodecException::class.java) { EnvironmentProfileCodec.decode("not-json") }
        val future = EnvironmentProfileCodec.encode(profile.copy(schemaVersion = 1)).replace("\"schemaVersion\":1", "\"schemaVersion\":99")
        assertThrows(EnvironmentCodecException::class.java) { EnvironmentProfileCodec.decode(future) }
    }

    @Test fun oversizedProfilesAreRejectedForBothImportAndExport() {
        assertThrows(EnvironmentCodecException::class.java) {
            EnvironmentProfileCodec.decode(" ".repeat(EnvironmentProfileCodec.MAX_PROFILE_BYTES + 1))
        }
        val oversized = profile.copy(wifi = listOf(profile.wifi.single().copy(
            capabilities = "x".repeat(EnvironmentProfileCodec.MAX_PROFILE_BYTES),
        )))
        assertThrows(IllegalArgumentException::class.java) { EnvironmentProfileCodec.encode(oversized) }
    }

    @Test fun profileLevelSchemaValidationRejectsUnknownVersion() {
        assertThrows(IllegalArgumentException::class.java) { profile.copy(schemaVersion = 99) }
    }

    @Test fun defaultSchemaVersionIsCurrent() {
        assertEquals(EnvironmentProfile.SCHEMA_VERSION, profile.schemaVersion)
        assertEquals(1, EnvironmentProfile.SCHEMA_VERSION)
    }
}
