package com.system.location.service.hook

import org.junit.Assert.*
import org.junit.Test

class SampleCommandTest {
    @Test fun malformedScenarioIsRejectedBeforeAnyLeaseOrSamplePublication() {
        assertNull(SampleCommand.scenarioId(null))
        assertEquals("route", SampleCommand.scenarioId("route"))
        assertEquals(255, SampleCommand.scenarioId("x".repeat(255))!!.length)
        for (value in listOf("", " ", "x".repeat(256), 1, true)) {
            assertThrows(IllegalArgumentException::class.java) { SampleCommand.scenarioId(value) }
        }
    }
}
