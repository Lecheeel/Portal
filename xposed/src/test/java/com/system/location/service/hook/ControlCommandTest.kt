package com.system.location.service.hook

import com.system.location.service.core.geo.Wgs84
import org.junit.Assert.*
import org.junit.Test

class ControlCommandTest {
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
