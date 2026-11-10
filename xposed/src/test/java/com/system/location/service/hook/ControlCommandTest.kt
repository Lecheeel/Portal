package com.system.location.service.hook

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.runtime.RuntimeSnapshot
import com.system.location.service.hook.utils.FakeLoc
import org.junit.Assert.*
import org.junit.Test

class ControlCommandTest {
    @Test fun numericControlsRejectMissingWrongTypesAndInvalidValuesBeforeAnyPublication() {
        val saved = FakeLoc.runtimeSnapshots.current()
        try {
            val initial = RuntimeSnapshot(configRevision = 7, latitude = 31.0, longitude = 121.0)
            FakeLoc.runtimeSnapshots.set(initial)
            for (key in listOf("speed", "altitude", "bearing", "speed_amplitude")) {
                for (value in listOf(null, 1, 1f, "1", Double.NaN, Double.POSITIVE_INFINITY)) {
                    assertThrows(IllegalArgumentException::class.java) { ControlCommand.publishNumber(key, value) }
                    assertSame(initial, FakeLoc.runtimeSnapshots.current())
                }
            }
            assertThrows(IllegalArgumentException::class.java) { ControlCommand.publishNumber("speed", -1.0) }
            assertThrows(IllegalArgumentException::class.java) { ControlCommand.publishNumber("speed_amplitude", 10.1) }
            val bearing = ControlCommand.publishNumber("bearing", 270.0)
            assertTrue(bearing.hasBearings)
            assertEquals(270.0, bearing.bearingDeg, 0.0)
            assertEquals(8L, bearing.configRevision)
            val altitude = ControlCommand.publishNumber("altitude", -100.0)
            assertEquals(-100.0, altitude.altitudeMeters, 0.0)
            assertEquals(270.0, altitude.bearingDeg, 0.0)
            assertEquals(9L, altitude.configRevision)
        } finally { FakeLoc.runtimeSnapshots.set(saved) }
    }

    @Test fun startRequiresRealTypedCoordinatesAndValidOptionalFields() {
        val valid = mapOf<String, Any?>("lat" to 31.0, "lon" to 121.0)
        val prepared = ControlCommand.prepareStart(valid + mapOf("speed" to 4.0, "accuracy" to 8f, "report_interval" to 200L))
        assertEquals(Wgs84(31.0, 121.0), prepared.coordinate)
        assertEquals(4.0, prepared.speedMps!!, 0.0)
        assertEquals(8f, prepared.accuracyMeters!!)
        assertEquals(200L, prepared.reportIntervalMs)
        assertNull(ControlCommand.prepareStart(valid).speedMps)
        assertThrows(IllegalArgumentException::class.java) { ControlCommand.prepareStart(emptyMap()) }
        val invalid = mapOf<String, Any?>("lat" to 1f, "lon" to Double.NaN, "speed" to -1.0,
            "accuracy" to 0f, "altitude" to Double.POSITIVE_INFINITY, "report_interval" to 10L)
        invalid.forEach { (key, value) ->
            assertThrows("Must reject $key", IllegalArgumentException::class.java) {
                ControlCommand.prepareStart(valid + (key to value))
            }
            assertThrows("Must reject null $key", IllegalArgumentException::class.java) {
                ControlCommand.prepareStart(valid + (key to null))
            }
        }
    }
}
