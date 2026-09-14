package com.system.location.service.backend

import com.system.location.service.backend.mock.*
import com.system.location.service.core.backend.BackendResult
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.location.LocationSample
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MockProviderBackendTest {
    private class Port : MockProviderPort {
        var allowed = true
        val registered = linkedSetOf<String>()
        val enabled = mutableSetOf<String>()
        val fixes = mutableListOf<Pair<String, LocationSample>>()
        var failRegister: String? = null
        var failEnable: String? = null
        var failPublish = false
        var failRemove = false
        override fun permissionGranted() = allowed
        override fun pendingProviders() = registered.toSet()
        override fun register(provider: String) {
            registered += provider
            if (failRegister == provider) error("register failed after allocation")
        }
        override fun enable(provider: String, enabled: Boolean) {
            if (enabled && failEnable == provider) error("enable failed")
            if (enabled) this.enabled += provider else this.enabled -= provider
        }
        override fun publish(provider: String, sample: LocationSample) {
            if (failPublish) throw SecurityException("mock authorization revoked")
            fixes += provider to sample
        }
        override fun remove(provider: String) {
            if (failRemove) throw SecurityException("cleanup denied")
            registered -= provider
        }
    }
    private val sample = LocationSample(Wgs84(25.123456789123, 119.12345678912), 15.0, 2f, 3f, 45f, 1, 1)

    @Test fun pointAndPausedSamplesShareCompleteLifecycle() = runBlocking {
        val port = Port(); val backend = MockProviderBackend(port)
        assertTrue(backend.start() is BackendResult.Failure)
        assertEquals(BackendResult.Success, backend.prepare())
        assertEquals(BackendResult.Success, backend.start())
        backend.publish(sample)
        assertEquals(setOf("gps", "network"), port.enabled)
        assertEquals(listOf(sample, sample), port.fixes.map { it.second })
        assertEquals(BackendResult.Success, backend.pause())
        backend.publish(sample)
        assertEquals(0f, port.fixes.last().second.speed)
        assertEquals(sample.coordinate, port.fixes.last().second.coordinate)
        assertEquals(BackendResult.Success, backend.resume())
        assertEquals(BackendResult.Success, backend.stop())
        assertEquals(BackendResult.Success, backend.release())
        assertTrue(port.registered.isEmpty()); assertTrue(port.enabled.isEmpty())
    }
    @Test fun permissionDeniedDoesNotAllocateProviders() = runBlocking {
        val port = Port().apply { allowed = false }; val backend = MockProviderBackend(port)
        assertTrue(backend.prepare() is BackendResult.Failure)
        assertTrue(backend.start() is BackendResult.Failure)
        assertTrue(port.registered.isEmpty())
    }
    @Test fun partialRegistrationAndEnableFailuresRollbackBothProviders() = runBlocking {
        for (failEnable in listOf(false, true)) {
            val port = Port().apply { if (failEnable) this.failEnable = "network" else failRegister = "network" }
            val backend = MockProviderBackend(port)
            backend.prepare()
            assertTrue(backend.start() is BackendResult.Failure)
            assertTrue(port.registered.isEmpty()); assertTrue(port.enabled.isEmpty())
        }
    }
    @Test fun publishFailureCleansProvidersAndCleanupCanRetry() = runBlocking {
        val port = Port(); val backend = MockProviderBackend(port)
        backend.prepare(); backend.start(); port.failPublish = true; port.failRemove = true
        assertTrue(backend.publish(sample) is BackendResult.Failure)
        assertTrue(backend.release() is BackendResult.Failure)
        assertEquals(2, port.registered.size)
        port.failRemove = false
        assertEquals(BackendResult.Success, backend.release())
        assertTrue(port.registered.isEmpty())
    }
    @Test fun preparingAfterProcessInterruptionCleansJournaledProviders() = runBlocking {
        val port = Port().apply { registered += "gps" }
        val backend = MockProviderBackend(port)
        assertEquals(BackendResult.Success, backend.prepare())
        assertTrue(port.registered.isEmpty())
        backend.start(); backend.stop()
        assertTrue(port.registered.isEmpty())
    }

    @Test fun stopAfterProcessRecreationCleansJournalWithoutStartingProviders() = runBlocking {
        val port = Port().apply { registered += "gps"; failRemove = true }
        val backend = MockProviderBackend(port)
        assertTrue(backend.stop() is BackendResult.Failure)
        port.failRemove = false
        assertEquals(BackendResult.Success, backend.stop())
        assertTrue(port.registered.isEmpty())
        assertTrue(port.fixes.isEmpty())
    }
}
