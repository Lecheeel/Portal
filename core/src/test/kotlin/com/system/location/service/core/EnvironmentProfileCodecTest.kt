package com.system.location.service.core

import com.system.location.service.core.environment.BleBeacon
import com.system.location.service.core.environment.CellTower
import com.system.location.service.core.environment.CellType
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
        generatedAtMillis = 1_790_000_000_000,
        seed = 7,
        wifi = listOf(
            WifiAp("ChinaNet", "A4:50:46:12:34:56", -55, 2412, 1, connected = true),
        ),
        cells = listOf(
            CellTower(CellType.LTE, 460, 0, 123, 456, pci = 1, rsrp = -90, registered = true),
        ),
        ble = listOf(BleBeacon("C4:50:46:12:34:56", "Beacon", -70)),
    )

    @Test fun roundTrips() {
        assertEquals(profile, EnvironmentProfileCodec.decode(EnvironmentProfileCodec.encode(profile)))
    }

    @Test fun unknownFieldsAreIgnored() {
        val raw = EnvironmentProfileCodec.encode(profile).trimEnd().removeSuffix("}") + ",\"future\":1}"
        assertEquals(profile, EnvironmentProfileCodec.decode(raw))
    }

    @Test fun invalidJsonAndFutureVersionAreRejected() {
        assertThrows(EnvironmentCodecException::class.java) { EnvironmentProfileCodec.decode("not-json") }
        val future = EnvironmentProfileCodec.encode(profile.copy(schemaVersion = 1)).replace("\"schemaVersion\":1", "\"schemaVersion\":99")
        assertThrows(EnvironmentCodecException::class.java) { EnvironmentProfileCodec.decode(future) }
    }

    @Test fun profileLevelSchemaValidationRejectsUnknownVersion() {
        assertThrows(IllegalArgumentException::class.java) { profile.copy(schemaVersion = 99) }
    }

    @Test fun defaultSchemaVersionIsCurrent() {
        assertEquals(EnvironmentProfile.SCHEMA_VERSION, profile.schemaVersion)
        assertEquals(1, EnvironmentProfile.SCHEMA_VERSION)
    }
}
