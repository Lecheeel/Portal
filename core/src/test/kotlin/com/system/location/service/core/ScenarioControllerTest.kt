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
        var diagnosticDetails = emptyList<BackendDiagnostic>()
        override suspend fun diagnose() = diagnosticDetails
        var failPublish = false
        var failCleanup = false
        override suspend fun prepare() = BackendResult.Success.also { calls += "prepare" }
        override suspend fun start() = BackendResult.Success.also { calls += "start" }
        override suspend fun pause() = BackendResult.Success.also { calls += "pause" }
        override suspend fun publish(sample: LocationSample, scenarioId: String?): BackendResult {
            calls += "publish"
            return if (failPublish) BackendResult.Failure("PUBLISH", "injected failure") else BackendResult.Success
        }
        override suspend fun stop(): BackendResult {
            calls += "stop"
            return if (failCleanup) BackendResult.Failure("CLEANUP", "denied") else BackendResult.Success
        }
        override suspend fun release(): BackendResult {
            calls += "release"
            return if (failCleanup) BackendResult.Failure("CLEANUP", "denied") else BackendResult.Success
        }
    }
    private val scenario = Scenario("s", "test", route = Route("r", "route", listOf(Wgs84(0.0, 0.0), Wgs84(0.01, 0.01))))

    @Test fun startupGenerationSurvivesShortSessionsBackendSelectionAndFailedPublication() = runBlocking {
        val backend = Backend()
        val c = ScenarioController({ backend }, Clock())
        assertEquals(0L, c.startGeneration)
        repeat(3) { index ->
            assertTrue(c.start(scenario))
            assertTrue(c.stop())
            assertFalse(c.state.value.isActive)
            assertEquals(index + 1L, c.startGeneration)
        }
        assertTrue(c.selectBackend(BackendType.MOCK_PROVIDER))
        assertEquals(3L, c.startGeneration)
        backend.failPublish = true
        assertFalse(c.start(scenario))
        assertFalse(c.state.value.isActive)
        assertEquals(4L, c.startGeneration)
    }

    @Test fun repeatedFailureAfterRecoveryIsRecordedWithinFiveSeconds() = runBlocking {
        assertOrdinaryDiagnosticTransitions(listOf("FAILED", "CONNECTED", "FAILED"))
    }

    @Test fun repeatedRecoveryAfterFailureIsRecordedWithinFiveSeconds() = runBlocking {
        assertOrdinaryDiagnosticTransitions(listOf("CONNECTED", "FAILED", "CONNECTED"))
    }

    private suspend fun assertOrdinaryDiagnosticTransitions(results: List<String>) {
        val backend = Backend()
        val clock = Clock()
        val accepted = mutableListOf<DiagnosticEvent>()
        val c = ScenarioController({ backend }, clock, diagnosticBatchSink = { accepted += it })
        results.forEachIndexed { index, result ->
            clock.time = index * 1000L
            backend.diagnosticDetails = listOf(BackendDiagnostic("REMOTE_COMMAND", result,
                if (result == "FAILED") "connection lost" else "connection restored"))
            c.refreshDiagnostics()
        }
        assertEquals(results, accepted.map { it.result })
        assertEquals(results, c.diagnostics.value.map { it.result })
    }

    @Test fun unchangedOrdinaryDiagnosticsUseTheFiveSecondWindow() = runBlocking {
        val backend = Backend().apply {
            diagnosticDetails = listOf(BackendDiagnostic("REMOTE_COMMAND", "CONNECTED", "connection restored"))
        }
        val clock = Clock()
        val accepted = mutableListOf<DiagnosticEvent>()
        val c = ScenarioController({ backend }, clock, diagnosticBatchSink = { accepted += it })
        c.refreshDiagnostics()
        clock.time = 4999
        c.refreshDiagnostics()
        assertEquals(1, accepted.size)
        clock.time = 5000
        c.refreshDiagnostics()
        assertEquals(listOf(0L, 5000L), accepted.map { it.timestamp })
    }

    @Test fun hookDisappearanceAndReappearanceAreRecorded() = runBlocking {
        val backend = Backend()
        val clock = Clock()
        val accepted = mutableListOf<DiagnosticEvent>()
        val c = ScenarioController({ backend }, clock, diagnosticBatchSink = { accepted += it })
        val hook = BackendDiagnostic("HOOK_POINT", "INFO", "hook A")
        backend.diagnosticDetails = listOf(hook)
        c.refreshDiagnostics()
        clock.time = 1000
        backend.diagnosticDetails = emptyList()
        c.refreshDiagnostics()
        clock.time = 2000
        backend.diagnosticDetails = listOf(hook)
        c.refreshDiagnostics()
        assertEquals(listOf(0L, 2000L), accepted.map { it.timestamp })
    }

    @Test fun rejectedBatchCanBeRetriedOnTheNextDiagnosis() = runBlocking {
        val backend = Backend().apply {
            diagnosticDetails = (1..60).map { BackendDiagnostic("HOOK_POINT", "INFO", "hook $it") }
        }
        var reject = true
        var hooksWritten = 0
        val c = ScenarioController({ backend }, Clock(), diagnosticBatchSink = { batch ->
            if (batch.any { it.stage == "HOOK_POINT" }) {
                if (reject) throw java.util.concurrent.RejectedExecutionException("full")
                hooksWritten += batch.size
            }
        })
        assertTrue(c.start(scenario))
        assertEquals(0, hooksWritten)
        assertTrue(c.diagnostics.value.any { it.stage == "DIAGNOSTIC_STORAGE" })
        reject = false
        c.refreshDiagnostics()
        assertEquals(60, hooksWritten)
    }

    @Test fun asynchronousStorageFailuresAreReportedWithoutReenteringTheSink() = runBlocking {
        var submitted = 0
        val c = ScenarioController({ Backend() }, Clock(), diagnosticBatchSink = { submitted += it.size })
        c.reportDiagnosticStorageFailure(IllegalStateException("disk full"))
        c.reportDiagnosticStorageFailure(IllegalStateException("still full"))
        assertEquals(1, c.diagnostics.value.count { it.stage == "DIAGNOSTIC_STORAGE" })
        assertEquals(0, submitted)
        c.reportDiagnosticStorageFailure(null)
        c.reportDiagnosticStorageFailure(IllegalStateException("another failure"))
        assertEquals(2, c.diagnostics.value.count { it.stage == "DIAGNOSTIC_STORAGE" })
        assertEquals(0, submitted)
    }

    @Test fun oneDiagnoseRoundIsSubmittedAsOneBatch() = runBlocking {
        val backend = Backend().apply {
            diagnosticDetails = (1..60).map { BackendDiagnostic("HOOK_POINT", "INFO", "hook $it") }
        }
        val batches = mutableListOf<List<DiagnosticEvent>>()
        val c = ScenarioController({ backend }, Clock(), diagnosticBatchSink = { batches += it })
        assertTrue(c.start(scenario))
        assertEquals(1, batches.count { batch -> batch.any { it.stage == "HOOK_POINT" } })
        assertEquals(60, batches.single { batch -> batch.any { it.stage == "HOOK_POINT" } }.size)
    }

    @Test fun unchangedHooksRemainDeduplicatedWhenPeriodicHistoryRollsOver() = runBlocking {
        val clock = Clock()
        val backend = Backend().apply {
            diagnosticDetails = (1..60).map { BackendDiagnostic("HOOK_POINT", "INFO", "hook $it") }
        }
        var hooksWritten = 0
        val c = ScenarioController({ backend }, clock, diagnosticBatchSink = { batch ->
            hooksWritten += batch.count { it.stage == "HOOK_POINT" }
        })
        assertTrue(c.start(Scenario("p", "point", point = Wgs84(0.0, 0.0))))
        repeat(350) { clock.time += 5000; assertTrue(c.tick()) }
        assertEquals(60, hooksWritten)
        assertEquals(300, c.diagnostics.value.size)
    }

    @Test fun hookDetailsKeepStableIdentityAcrossFiveSecondPolls() = runBlocking {
        val clock = Clock()
        val backend = Backend().apply {
            diagnosticDetails = (1..60).map { BackendDiagnostic("HOOK_POINT", "INFO", "hook $it") }
        }
        val c = ScenarioController({ backend }, clock)
        assertTrue(c.start(scenario))
        assertEquals(60, c.diagnostics.value.count { it.stage == "HOOK_POINT" })
        clock.time = 5000
        assertTrue(c.tick())
        assertEquals(60, c.diagnostics.value.count { it.stage == "HOOK_POINT" })
        backend.diagnosticDetails = backend.diagnosticDetails.reversed()
        clock.time = 10000
        assertTrue(c.tick())
        assertEquals(60, c.diagnostics.value.count { it.stage == "HOOK_POINT" })
        backend.diagnosticDetails = backend.diagnosticDetails.map {
            if (it.reason == "hook 30") it.copy(suggestion = "new guidance") else it
        }
        clock.time = 15000
        assertTrue(c.tick())
        assertEquals(61, c.diagnostics.value.count { it.stage == "HOOK_POINT" })
        backend.diagnosticDetails = backend.diagnosticDetails.map { it.copy(suggestion = "") }
        clock.time = 20000
        assertTrue(c.tick())
        assertEquals(62, c.diagnostics.value.count { it.stage == "HOOK_POINT" })
    }

    @Test fun rebuildRetainsRouteProgressSessionAndPauseState() = runBlocking {
        val clock = Clock(); val backend = Backend(); val c = ScenarioController({ backend }, clock)
        assertFalse(c.rebuildMockProviders())
        assertTrue(c.start(scenario))
        clock.time = 2000; c.tick(); c.pause()
        val previous = c.state.value
        backend.calls.clear()
        clock.time = 4000
        assertTrue(c.rebuildMockProviders())
        assertEquals(listOf("stop", "prepare", "start", "pause", "publish"), backend.calls)
        assertEquals(RuntimePhase.PAUSED, c.state.value.phase)
        assertEquals(previous.scenarioId, c.state.value.scenarioId)
        assertEquals(previous.startedAt, c.state.value.startedAt)
        assertEquals(previous.sample!!.coordinate, c.state.value.sample!!.coordinate)
        assertEquals(previous.progress, c.state.value.progress, 0.0)
        assertTrue(c.resume())
        clock.time = 5000; assertTrue(c.tick())
        assertTrue(c.state.value.progress > previous.progress)
    }

    @Test fun failedRebuildCleanupNeverRegistersAgain() = runBlocking {
        val backend = Backend(); val c = ScenarioController({ backend }, Clock())
        assertTrue(c.start(scenario)); backend.calls.clear(); backend.failCleanup = true
        assertFalse(c.rebuildMockProviders())
        assertFalse(backend.calls.contains("prepare"))
        assertFalse(backend.calls.contains("start"))
        assertEquals(RuntimePhase.ERROR, c.state.value.phase)
        backend.failCleanup = false
        assertTrue(c.stop())
        assertFalse(c.rebuildMockProviders())
    }

    @Test fun speedChangesSettleOldMotionForBothRoutesAndJoystick() = runBlocking {
        for (route in listOf(false, true)) {
            val clock = Clock()
            val c = ScenarioController({ Backend() }, clock)
            val scene = if (route) scenario else Scenario("p", "point", point = Wgs84(0.0, 0.0))
            assertTrue(c.start(scene.copy(profile = MovementProfile(speedMps = 2.0))))
            if (route) assertFalse(c.setMotion(90.0, true)) else assertTrue(c.setMotion(90.0, true))
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
        assertEquals(listOf("stop"), backend.calls.takeLast(1))
        assertTrue(c.stop())
    }

    @Test fun publishFailureCleansResourcesAndCanRestart() = runBlocking {
        val backend = Backend(); val c = ScenarioController({ backend }, Clock())
        backend.failPublish = true
        assertFalse(c.start(scenario)); assertEquals(RuntimePhase.ERROR, c.state.value.phase)
        assertEquals(listOf("stop"), backend.calls.takeLast(1))
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
