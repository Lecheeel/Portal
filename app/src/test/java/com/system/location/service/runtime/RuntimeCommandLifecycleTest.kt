package com.system.location.service.runtime

import com.system.location.service.core.backend.*
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.runtime.*
import com.system.location.service.core.scenario.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RuntimeCommandLifecycleTest {
    private class Clock : RuntimeClock {
        var time = 0L
        override fun nanos() = time * 1_000_000
        override fun millis() = time
    }
    private class Backend : LocationBackend {
        var failPublish = false
        var failCleanup = false
        override val type = BackendType.MOCK_PROVIDER
        override val capabilities = emptyMap<Capability, CapabilityStatus>()
        override suspend fun prepare() = BackendResult.Success
        override suspend fun start() = BackendResult.Success
        override suspend fun publish(sample: LocationSample): BackendResult =
            if (failPublish) BackendResult.Failure("PUBLISH", "failed") else BackendResult.Success
        override suspend fun stop(): BackendResult =
            if (failCleanup) BackendResult.Failure("CLEANUP", "denied") else BackendResult.Success
        override suspend fun release() = stop()
    }
    @Test fun failedCommandsCloseServiceButRetainOwnershipForCleanupRetry() = runBlocking {
        for (command in listOf("pause", "resume", "motion", "speed", "stop", "tick")) {
            val backend = Backend()
            val clock = Clock()
            val controller = ScenarioController({ backend }, clock)
            var interrupted = true
            var finishes = 0
            val commands = RuntimeCommandLifecycle({ controller.state.value }, { interrupted = false }, { finishes++ })
            assertTrue(controller.start(Scenario("s", "point", point = Wgs84(0.0, 0.0))))
            if (command == "resume") assertTrue(controller.pause())
            backend.failPublish = true
            backend.failCleanup = true
            commands.execute {
                when (command) {
                    "pause" -> controller.pause()
                    "resume" -> controller.resume()
                    "motion" -> controller.setMotion(90.0, true)
                    "speed" -> controller.setSpeed(5.0)
                    "stop" -> controller.stop()
                    else -> controller.tick()
                }
            }
            assertEquals(command, RuntimePhase.ERROR, controller.state.value.phase)
            assertEquals(command, 1, finishes)
            assertTrue(command, interrupted)
            assertFalse(command, controller.selectBackend(BackendType.XPOSED))
            // Recreated process reads the retained marker and retries authoritative cleanup.
            val recovered = ScenarioController({ backend }, clock)
            recovered.recoverInterrupted(BackendType.MOCK_PROVIDER, "process died")
            assertEquals(RuntimePhase.ERROR, recovered.state.value.phase)
            assertFalse(recovered.selectBackend(BackendType.XPOSED))
            backend.failCleanup = false
            assertTrue(commands.execute { controller.stop() })
            assertFalse(interrupted)
            assertEquals(2, finishes)
            assertTrue(controller.selectBackend(BackendType.XPOSED))
            assertTrue(recovered.stop())
        }
    }

    @Test fun activeRejectedCommandDoesNotFinishService() = runBlocking {
        val controller = ScenarioController({ Backend() }, Clock())
        var finishes = 0
        val commands = RuntimeCommandLifecycle({ controller.state.value }, { fail("active marker cleared") }, { finishes++ })
        controller.start(Scenario("s", "point", point = Wgs84(0.0, 0.0)))
        assertFalse(commands.execute { controller.setMotion(Double.NaN, true) })
        assertEquals(0, finishes)
    }

    @Test fun inactiveTickStillFinishesService() = runBlocking {
        val controller = ScenarioController({ Backend() }, Clock())
        var finishes = 0
        val commands = RuntimeCommandLifecycle({ controller.state.value }, {}, { finishes++ })
        commands.execute { if (controller.state.value.isActive) controller.tick() }
        assertEquals(1, finishes)
    }

    @Test fun terminalExceptionStillFinishesService() = runBlocking {
        val controller = ScenarioController({ Backend() }, Clock())
        var finishes = 0
        val commands = RuntimeCommandLifecycle({ controller.state.value }, {}, { finishes++ })
        controller.start(Scenario("s", "point", point = Wgs84(0.0, 0.0)))
        assertTrue(runCatching { commands.execute { controller.stop(); error("after stop") } }.isFailure)
        assertEquals(1, finishes)
    }

    @Test fun pauseCompletingRouteClosesServiceAndClearsInterruptedMarker() = runBlocking {
        val clock = Clock()
        val controller = ScenarioController({ Backend() }, clock)
        var interrupted = true
        var finishes = 0
        val commands = RuntimeCommandLifecycle({ controller.state.value }, { interrupted = false }, { finishes++ })
        assertTrue(controller.start(Scenario("s", "route", route = Route("r", "route",
            listOf(Wgs84(0.0, 0.0), Wgs84(0.00001, 0.0))))))
        clock.time = 1_000_000
        commands.execute { controller.pause() }
        assertEquals(RuntimePhase.STOPPED, controller.state.value.phase)
        assertEquals(1, finishes)
        assertFalse(interrupted)
    }
}
