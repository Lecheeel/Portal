package com.system.location.service.core

import com.system.location.service.core.backend.*
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.runtime.*
import com.system.location.service.core.scenario.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import net.sf.geographiclib.Geodesic

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

    @Test fun speedChangesSettleOldMotionForBothRoutesAndJoystick() = runBlocking {
        for (route in listOf(false, true)) {
            val clock = Clock()
            val c = ScenarioController({ Backend() }, clock)
            val scene = if (route) scenario else Scenario("p", "point", point = Wgs84(0.0, 0.0))
            assertTrue(c.start(scene.copy(profile = MovementProfile(speedMps = 2.0))))
            if (!route) assertTrue(c.setMotion(90.0, true))
            val startedAt = c.state.value.startedAt
            clock.time = 1000
            assertTrue(c.setSpeed(8.0))
            fun distance(): Double = c.state.value.sample!!.let {
                Geodesic.WGS84.Inverse(0.0, 0.0, it.latitude, it.longitude).s12
            }
            assertEquals(2.0, distance(), 1e-5)
            assertEquals(8f, c.state.value.sample!!.speed)
            assertEquals(8.0, c.state.value.configuredSpeedMps!!, 0.0)
            assertEquals(startedAt, c.state.value.startedAt)
            clock.time = 2000
            c.tick()
            assertEquals(10.0, distance(), 1e-5)
            assertTrue(c.pause())
            val paused = c.state.value.sample!!.coordinate
            clock.time = 50000
            assertTrue(c.setSpeed(4.0))
            assertEquals(paused, c.state.value.sample!!.coordinate)
            assertEquals(0f, c.state.value.sample!!.speed)
            assertEquals(4.0, c.state.value.configuredSpeedMps!!, 0.0)
            assertTrue(c.resume())
            clock.time = 51000
            c.tick()
            assertEquals(14.0, distance(), 1e-5)
            assertTrue(c.setSpeed(0.0))
            clock.time = 60000
            c.tick()
            assertEquals(14.0, distance(), 1e-5)
            assertFalse(c.setSpeed(Double.NaN))
            assertFalse(c.setSpeed(-1.0))
            assertFalse(c.setSpeed(1001.0))
            assertEquals(0.0, c.state.value.configuredSpeedMps!!, 0.0)
            c.stop()
            assertFalse(c.setSpeed(10.0))
        }
    }

    @Test fun speedChangeHandlesCompletionAndPublishFailure() = runBlocking {
        val clock = Clock(); val backend = Backend(); val c = ScenarioController({ backend }, clock)
        c.start(scenario)
        clock.time = 1_000_000
        assertFalse(c.setSpeed(10.0))
        assertEquals(RuntimePhase.STOPPED, c.state.value.phase)
        c.start(scenario)
        backend.failPublish = true
        assertFalse(c.setSpeed(10.0))
        assertEquals(RuntimePhase.ERROR, c.state.value.phase)
    }

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
