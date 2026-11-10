package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.providers.ProviderKind
import com.system.location.service.core.runtime.RuntimeSnapshot
import com.system.location.service.core.runtime.RuntimeSnapshotStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RuntimeSnapshotTest {
    private val sample = LocationSample(
        coordinate = Wgs84(31.2304, 121.4737),
        altitude = 80.0,
        accuracy = 6f,
        speed = 1f,
        bearing = 90f,
        timeMillis = 1_000,
        elapsedNanos = 1_000,
    )

    @Test fun emptySnapshotHasNoSample() {
        assertNull(RuntimeSnapshot.EMPTY.sample)
        assertEquals(0L, RuntimeSnapshot.EMPTY.configRevision)
    }

    @Test fun publishSampleUpdatesAtomically() {
        val store = RuntimeSnapshotStore()
        val updated = store.publishSample(sample, ProviderKind.GPS, configRevision = 3)
        assertEquals(3L, updated.configRevision)
        assertEquals(sample, store.current().sample)
        assertEquals(ProviderKind.GPS, store.current().provider)
    }

    @Test fun updatePreservesOtherFields() {
        val store = RuntimeSnapshotStore(RuntimeSnapshot(configRevision = 2, environmentVersion = 4))
        val updated = store.update { it.copy(physicalNoise = true) }
        assertEquals(2L, updated.configRevision)
        assertEquals(4L, updated.environmentVersion)
        assertEquals(true, updated.physicalNoise)
    }

    @Test fun clearSampleKeepsConfig() {
        val store = RuntimeSnapshotStore(RuntimeSnapshot(configRevision = 5, sample = sample))
        val cleared = store.clearSample()
        assertNull(cleared.sample)
        assertNull(cleared.provider)
        assertEquals(5L, cleared.configRevision)
    }

    @Test fun invalidVersionsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { RuntimeSnapshot(configRevision = -1) }
        assertThrows(IllegalArgumentException::class.java) { RuntimeSnapshot(environmentVersion = -1) }
        assertThrows(IllegalArgumentException::class.java) { RuntimeSnapshot(environmentApCount = 0) }
    }

    @Test fun configFieldsAreCaptured() {
        val snapshot = RuntimeSnapshot(
            configRevision = 9,
            environmentApCount = 7,
            cellMode = com.system.location.service.core.environment.CellMode.AUTO,
            environmentSourceMode = com.system.location.service.core.environment.EnvironmentSourceMode.REPLAY,
            simReady = false,
            physicalNoise = true,
            physicalNoiseSeed = 42,
        )
        assertEquals(7, snapshot.environmentApCount)
        assertEquals(com.system.location.service.core.environment.CellMode.AUTO, snapshot.cellMode)
        assertEquals(com.system.location.service.core.environment.EnvironmentSourceMode.REPLAY, snapshot.environmentSourceMode)
        assertEquals(false, snapshot.simReady)
        assertEquals(true, snapshot.physicalNoise)
        assertEquals(42L, snapshot.physicalNoiseSeed)
    }
}
