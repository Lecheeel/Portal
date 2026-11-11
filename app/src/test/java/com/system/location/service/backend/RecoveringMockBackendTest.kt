package com.system.location.service.backend

import com.system.location.service.backend.mock.*
import com.system.location.service.core.backend.*
import com.system.location.service.core.location.LocationSample
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RecoveringMockBackendTest {
    @Test fun recoveryWaitsForSuccessfulCleanupAndIsNotRepeated() = runBlocking {
        val events = mutableListOf<String>()
        var fail = true
        val delegate = object : LocationBackend {
            override val type = BackendType.MOCK_PROVIDER
            override val capabilities = emptyMap<Capability, CapabilityStatus>()
            override suspend fun prepare() = BackendResult.Success
            override suspend fun start() = BackendResult.Success
            override suspend fun publish(sample: LocationSample, scenarioId: String?) = BackendResult.Success
            override suspend fun stop(): BackendResult {
                events += "cleanup"
                return if (fail) BackendResult.Failure("CLEANUP", "pending") else BackendResult.Success
            }
            override suspend fun release() = stop()
        }
        val recovery = object : PhysicalLocationRecovery {
            override fun cancel() { events += "cancel" }
            override fun request() { events += "physical" }
        }
        val backend = RecoveringMockBackend(delegate, recovery)
        backend.prepare(); backend.start()
        assertTrue(backend.stop() is BackendResult.Failure)
        assertEquals(listOf("cancel", "cleanup"), events)
        fail = false
        backend.release(); backend.stop()
        assertEquals(listOf("cancel", "cleanup", "cleanup", "physical", "cleanup"), events)
        backend.prepare()
        assertEquals("cancel", events.last())
    }
}
