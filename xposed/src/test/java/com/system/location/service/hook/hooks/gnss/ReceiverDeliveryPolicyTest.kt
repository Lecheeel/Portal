package com.system.location.service.hook.hooks.gnss

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.routing.RoutingConfig
import com.system.location.service.core.runtime.HookConfiguration
import com.system.location.service.core.runtime.RuntimeSnapshot
import com.system.location.service.core.runtime.RuntimeSnapshotStore
import org.junit.Assert.*
import org.junit.Test

class ReceiverDeliveryPolicyTest {
    private val initial = RuntimeSnapshot(configRevision = 10,
        configuration = HookConfiguration(enabled = true, nmeaEnabled = true))

    @Test fun sampleRenewalDoesNotSplitACapturedBatchButStopAndRestartInvalidateIt() {
        val store = RuntimeSnapshotStore(initial)
        store.publishSample(LocationSample(Wgs84(31.0, 121.0), 0.0, 5f, 1f, 0f, 1000, 1000))
        assertTrue(receiverDeliveryAllowed(initial, store.current(), nmea = true))
        store.update { it.copy(configRevision = 11, configuration = it.configuration.copy(enabled = false)) }
        assertFalse(receiverDeliveryAllowed(initial, store.current()))
        store.update { it.copy(configRevision = 12, configuration = it.configuration.copy(enabled = true)) }
        assertFalse("A resumed session cannot finish its predecessor's captured batch",
            receiverDeliveryAllowed(initial, store.current(), nmea = true))
    }

    @Test fun switchAndRouteChangesRejectTheOldBatchEvenIfTheirWriterFailedToBumpRevision() {
        for (current in listOf(
            initial.copy(configuration = initial.configuration.copy(gnssEnabled = false)),
            initial.copy(configuration = initial.configuration.copy(nmeaEnabled = false)),
            initial.copy(configuration = initial.configuration.copy(minimumSatellites = 20)),
            initial.copy(routing = RoutingConfig(globalScenarioId = "another-route")),
        )) assertFalse(receiverDeliveryAllowed(initial, current, nmea = true))
        val disabledNmea = initial.copy(configuration = initial.configuration.copy(nmeaEnabled = false))
        assertTrue("NMEA off must still allow a captured status batch", receiverDeliveryAllowed(disabledNmea, disabledNmea))
        assertFalse(receiverDeliveryAllowed(disabledNmea, disabledNmea, nmea = true))
    }
}
