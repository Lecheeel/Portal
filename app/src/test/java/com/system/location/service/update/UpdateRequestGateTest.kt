package com.system.location.service.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateRequestGateTest {
    @Test fun resultIsAcceptedOnlyForCurrentChannelAndLatestRequest() {
        val gate = UpdateRequestGate<String>("stable")
        val first = gate.begin()!!
        assertTrue(gate.accepts(first, "stable"))
        gate.select("beta")
        assertFalse(gate.accepts(first, "stable"))
        val beta = gate.begin()!!
        gate.select("stable")
        assertFalse(gate.accepts(beta, "beta"))
    }

    @Test fun newerSameChannelCheckInvalidatesOlderCompletion() {
        val gate = UpdateRequestGate<String>("stable")
        val first = gate.begin()!!
        val second = gate.begin()!!
        assertFalse(gate.accepts(first, "stable"))
        assertTrue(gate.accepts(second, "stable"))
    }

    @Test fun disabledChannelDoesNotStartRequests() {
        val gate = UpdateRequestGate<String>(null)
        assertNull(gate.begin())
    }
}
