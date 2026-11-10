package com.system.location.service.hook

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.runtime.*
import org.junit.Assert.*
import org.junit.Test

class LocationCommandTest {
    private val state = RuntimeSnapshot(latitude = 20.0, longitude = 100.0,
        speedMps = 4.0, configuration = HookConfiguration(enabled = true))

    @Test fun coordinateArithmeticUsesOneStateAndDividesCurrentCoordinateByOperand() {
        val operands = mapOf<String, Any?>("lat" to 2.0, "lon" to 4.0)
        val expected = mapOf("=" to Wgs84(2.0, 4.0), "+" to Wgs84(22.0, 104.0),
            "-" to Wgs84(18.0, 96.0), "*" to Wgs84(40.0, 100.0), "/" to Wgs84(10.0, 25.0))
        expected.forEach { (mode, point) ->
            val input = if (mode == "*") operands + ("lon" to 1.0) else operands
            val prepared = LocationCommand.prepareUpdate(input + ("mode" to mode), state)
            assertEquals(point, prepared.coordinate)
        }
        val equator = state.copy(latitude = 0.0, longitude = 0.0)
        assertEquals(Wgs84(0.0, 0.0), LocationCommand.prepareUpdate(operands + ("mode" to "/"), equator).coordinate)
    }

    @Test fun malformedOrOutOfRangeFieldsAreRejectedBeforePositionOrHeadingChanges() {
        val valid = mapOf<String, Any?>("mode" to "=", "lat" to 31.0, "lon" to 121.0,
            "bearing" to 90.0, "moving_speed" to 4f)
        for ((key, value) in mapOf("mode" to "unknown", "lat" to 91.0, "lon" to Double.NaN,
            "bearing" to 90f, "moving_speed" to Double.POSITIVE_INFINITY)) {
            assertThrows(Exception::class.java) { LocationCommand.prepareUpdate(valid + (key to value), state) }
        }
        for (key in listOf("mode", "lat", "lon")) {
            assertThrows(Exception::class.java) { LocationCommand.prepareUpdate(valid - key, state) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocationCommand.prepareUpdate(valid + mapOf("mode" to "/", "lat" to 0.0), state)
        }
        val prepared = LocationCommand.prepareUpdate(valid, state)
        assertEquals(90.0, prepared.bearingDeg!!, 0.0)
        assertEquals(4f, prepared.movementSpeedMps)
    }

    @Test fun movementUsesCapturedSpeedAndDoesNotReadAnotherLiveTarget() {
        val prepared = LocationCommand.prepareMove(mapOf("n" to 0.0, "bearing" to 270.0), state)
        assertEquals(Wgs84(20.0, 100.0), prepared.coordinate)
        assertEquals(0f, prepared.movementSpeedMps)
        assertEquals(4f, LocationCommand.prepareMove(mapOf("n" to 10.0, "bearing" to 90.0), state).movementSpeedMps)
        assertThrows(Exception::class.java) { LocationCommand.prepareMove(mapOf("n" to 10f, "bearing" to 90.0), state) }
        assertThrows(IllegalArgumentException::class.java) {
            LocationCommand.prepareMove(mapOf("n" to -1.0, "bearing" to 90.0), state)
        }
    }
}
