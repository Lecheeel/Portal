package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.runtime.*
import org.junit.Assert.*
import org.junit.Test

class OrderedRuntimeReceiverTest {
    @Test fun badNewerMessageDoesNotChangeLiveStateOrConsumeItsSequence() {
        var live = RuntimeTransfer(RuntimeSnapshot(), false)
        var restores = 0
        val receiver = OrderedRuntimeReceiver({ live.state.configuration.gnssSource }) { state, _ ->
            live = state; restores++
        }
        receiver.begin("session")
        val enabled = RuntimeTransfer(RuntimeSnapshot(configuration = HookConfiguration(enabled = true,
            minimumSatellites = 24, nmeaEnabled = false)), false)
        val good = RuntimeTransferWire.encode(enabled)
        assertTrue(receiver.receive("session", 1, good, 1))
        assertThrows(Exception::class.java) { receiver.receive("session", 2, byteArrayOf(0), 2) }
        assertSame(enabled.state.configuration.gnssSource, live.state.configuration.gnssSource)
        assertEquals(enabled, live)
        assertEquals(1, restores)
        val disabled = RuntimeTransferWire.encode(enabled.copy(state = enabled.state.copy(
            configuration = enabled.state.configuration.copy(enabled = false))))
        assertTrue(receiver.receive("session", 2, disabled, 3))
        assertFalse(live.state.configuration.enabled)
        assertFalse(receiver.receive("session", 1, good, 4))
        assertEquals(2, restores)
    }

    @Test fun delayedSamplesAreDisabledBeforeRestoreAndOldSessionNeverDecodes() {
        val sample = LocationSample(Wgs84(31.0, 121.0), 10.0, 5f, 2f, 90f, 1000, 1000)
        val encoded = RuntimeTransferWire.encode(RuntimeTransfer(RuntimeSnapshot(sample = sample,
            configuration = HookConfiguration(enabled = true)), true, mapOf("route" to sample)))
        var live: RuntimeTransfer? = null
        val receiver = OrderedRuntimeReceiver({ null }) { state, _ -> live = state }
        receiver.begin("new")
        assertFalse(receiver.receive("old", Long.MAX_VALUE, byteArrayOf(0), 2000))
        assertNull(live)
        assertTrue(receiver.receive("new", 1, encoded, 5_000_001_000))
        assertFalse(live!!.state.configuration.enabled)
        assertNull(live!!.state.sample)
        assertTrue(live!!.scenarios.isEmpty())
    }
}
