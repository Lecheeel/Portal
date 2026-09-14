package com.system.location.service.core

import com.system.location.service.core.backend.*
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.runtime.*
import com.system.location.service.core.scenario.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ScenarioControllerTest {
    private class Clock : RuntimeClock {
        var time = 0L
        override fun nanos() = time * 1_000_000
        override fun millis() = time
    }
    private class Backend : LocationBackend {
        override val type = BackendType.MOCK_PROVIDER
        override val capabilities = emptyMap<Capability, CapabilityStatus>()
        val calls = mutableListOf<String>()
        var failPublish = false
        var failCleanup = false
        override suspend fun prepare() = BackendResult.Success.also { calls += "prepare" }
        override suspend fun start() = BackendResult.Success.also { calls += "start" }
        override suspend fun publish(sample: LocationSample): BackendResult {
            calls += "publish"
            return if (failPublish) BackendResult.Failure("PUBLISH", "injected failure") else BackendResult.Success
        }
        override suspend fun stop() = BackendResult.Success.also { calls += "stop" }
        override suspend fun release(): BackendResult {
            calls += "release"
            return if (failCleanup) BackendResult.Failure("CLEANUP", "denied") else BackendResult.Success
        }
    }
    private val scenario = Scenario("s", "test", route = Route("r", "route", listOf(Wgs84(0.0, 0.0), Wgs84(0.01, 0.01))))

    @Test fun completeLifecycleAndSingleStateSource() = runBlocking {
        val clock = Clock(); val backend = Backend(); val c = ScenarioController({ backend }, clock)
        assertTrue(c.start(scenario)); assertEquals(RuntimePhase.RUNNING, c.state.value.phase)
        clock.time = 1000; c.tick()
        assertTrue(c.pause()); val point = c.state.value.sample!!.coordinate
        clock.time = 50000; c.tick(); assertEquals(point, c.state.value.sample!!.coordinate)
        assertFalse(c.selectBackend(BackendType.XPOSED))
        assertTrue(c.resume()); assertEquals(point, c.state.value.sample!!.coordinate)
        clock.time = 51000; c.tick(); assertNotEquals(point, c.state.value.sample!!.coordinate)
        assertTrue(c.stop()); assertEquals(RuntimePhase.STOPPED, c.state.value.phase)
        assertEquals(listOf("stop", "release"), backend.calls.takeLast(2))
        assertTrue(c.stop())
    }

    @Test fun publishFailureCleansResourcesAndCanRestart() = runBlocking {
        val backend = Backend(); val c = ScenarioController({ backend }, Clock())
        backend.failPublish = true
        assertFalse(c.start(scenario)); assertEquals(RuntimePhase.ERROR, c.state.value.phase)
        assertEquals(listOf("stop", "release"), backend.calls.takeLast(2))
        assertEquals("PUBLISH", c.diagnostics.value.last { it.result == "FAILED" }.stage)
        backend.failPublish = false
        assertTrue(c.start(scenario))
    }

    @Test fun failedCleanupPreventsSwitchUntilRetrySucceeds() = runBlocking {
        val backend = Backend(); val c = ScenarioController({ backend }, Clock())
        c.start(scenario); backend.failCleanup = true
        assertFalse(c.stop()); assertFalse(c.selectBackend(BackendType.XPOSED))
        backend.failCleanup = false
        assertTrue(c.stop()); assertTrue(c.selectBackend(BackendType.XPOSED))
    }

    @Test fun processInterruptionNeverReportsRunning() = runBlocking {
        val c = ScenarioController({ Backend() }, Clock())
        c.interrupted("进程已退出")
        assertEquals(RuntimePhase.ERROR, c.state.value.phase)
        assertFalse(c.state.value.isActive)
        assertEquals("PROCESS_INTERRUPTED", c.state.value.error!!.stage)
        assertTrue(c.stop())
        assertEquals(RuntimePhase.STOPPED, c.state.value.phase)
        assertNull(c.state.value.error)
    }

    @Test fun backendConstructionFailureIsReportedAndCanRecover() = runBlocking {
        var fail = true
        val c = ScenarioController({ if (fail) error("service unavailable") else Backend() }, Clock())
        assertFalse(c.start(scenario))
        assertEquals("CREATE_BACKEND", c.state.value.error!!.stage)
        assertFalse(c.state.value.isActive)
        fail = false
        assertTrue(c.start(scenario))
    }

    @Test fun interruptedProcessRetainsFailedBackendUntilResourcesAreReleased() = runBlocking {
        val backend = Backend().apply { failCleanup = true }
        val c = ScenarioController({ backend }, Clock())
        c.recoverInterrupted(BackendType.MOCK_PROVIDER, "process died")
        assertFalse(c.state.value.isActive)
        assertFalse(c.selectBackend(BackendType.XPOSED))
        assertEquals(listOf("stop", "release"), backend.calls)
        backend.failCleanup = false
        assertTrue(c.stop())
        assertTrue(c.selectBackend(BackendType.XPOSED))
    }
}
