package com.system.location.service

import android.location.Location
import com.system.location.service.backend.mock.MockFlagExperiment
import org.junit.Assert.*
import org.junit.Test

class MockFlagExperimentTest {
    @Test fun experimentPreservesLocationDataAndReportsLocalScope() {
        val location = Location("gps").apply {
            latitude = 25.123456
            longitude = 119.123456
            altitude = 80.0
            accuracy = 25f
            speed = 0f
            bearing = 90f
            time = 12345L
            elapsedRealtimeNanos = 67890L
            isMock = true
        }
        val outcome = MockFlagExperiment().clear(location)
        if (outcome.invoked) assertFalse(location.isMock)
        assertTrue(outcome.detail.contains("提交前"))
        assertEquals(25.123456, location.latitude, 0.0)
        assertEquals(119.123456, location.longitude, 0.0)
        assertEquals(80.0, location.altitude, 0.0)
        assertEquals(25f, location.accuracy)
        assertEquals(0f, location.speed)
        assertEquals(90f, location.bearing)
        assertEquals(12345L, location.time)
        assertEquals(67890L, location.elapsedRealtimeNanos)
    }

    @Test fun repeatedClearsAcceptAnAlreadyUnmarkedObject() {
        val experiment = MockFlagExperiment()
        val location = Location("network")
        repeat(3) {
            experiment.clear(location)
            assertFalse(location.isMock)
        }
    }
}
