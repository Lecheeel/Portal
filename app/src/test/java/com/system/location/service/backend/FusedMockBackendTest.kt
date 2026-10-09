package com.system.location.service.backend

import com.system.location.service.backend.mock.*
import com.system.location.service.core.backend.*
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.location.LocationSample
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FusedMockBackendTest {
    private class Base : LocationBackend {
        var published = 0; var stopped = 0; var fail = false
        override val type = BackendType.MOCK_PROVIDER
        override val capabilities = emptyMap<Capability, CapabilityStatus>()
        override suspend fun prepare() = BackendResult.Success
        override suspend fun start() = BackendResult.Success
        override suspend fun publish(sample: LocationSample): BackendResult { published++; return if (fail) BackendResult.Failure("PUBLISH", "denied") else BackendResult.Success }
        override suspend fun stop(): BackendResult { stopped++; return BackendResult.Success }
        override suspend fun release() = stop()
    }
    private class Fused : FusedMockPort {
        var failStart = false; var failPublish = false; var failStop = false
        var published = 0; var stopped = 0; var pending = false
        override val capability = CapabilityStatus(Availability.EXPERIMENTAL)
        override fun diagnostics() = emptyList<BackendDiagnostic>()
        override suspend fun prepare() { if (pending) stop() }
        override suspend fun start() { pending = true; if (failStart) error("unavailable") }
        override suspend fun publish(sample: LocationSample) { published++; if (failPublish) error("failed") }
        override suspend fun stop() { stopped++; if (failStop) error("cleanup unavailable"); pending = false }
    }
    private val sample = LocationSample(Wgs84(1.0, 2.0), 10.0, 5f, 0f, 0f, 1, 1)
    @Test fun optionalStartupAndPublishFailureDoNotStopStandardProviders() = runBlocking {
        for (onStart in listOf(true, false)) {
            val base = Base(); val fused = Fused().apply { failStart = onStart; failPublish = !onStart }
            val backend = FusedMockBackend(base, fused)
            backend.prepare(); assertEquals(BackendResult.Success, backend.start())
            repeat(3) { assertEquals(BackendResult.Success, backend.publish(sample)) }
            assertEquals(3, base.published); assertEquals(if (onStart) 0 else 1, fused.published)
            assertTrue(backend.diagnose().any { it.stage == "GMS_DEGRADED" })
            assertEquals(BackendResult.Success, backend.stop()); assertFalse(fused.pending)
        }
    }
    @Test fun failedCleanupRetainsPendingStateAndRetriesWithoutStartingNewSession() = runBlocking {
        val base = Base(); val fused = Fused().apply { pending = true; failStop = true }
        val backend = FusedMockBackend(base, fused)
        assertTrue(backend.prepare() is BackendResult.Failure); assertTrue(fused.pending)
        assertTrue(backend.stop() is BackendResult.Failure); assertEquals(1, base.stopped)
        fused.failStop = false
        assertEquals(BackendResult.Success, backend.stop()); assertFalse(fused.pending)
        assertEquals(BackendResult.Success, backend.release())
    }
    @Test fun standardFailureIsAuthoritativeAndSkipsFusedPublishing() = runBlocking {
        val base = Base(); val fused = Fused(); val backend = FusedMockBackend(base, fused)
        backend.prepare(); backend.start(); base.fail = true
        assertTrue(backend.publish(sample) is BackendResult.Failure); assertEquals(0, fused.published)
        backend.stop(); assertEquals(1, base.stopped); assertFalse(fused.pending)
    }
}
