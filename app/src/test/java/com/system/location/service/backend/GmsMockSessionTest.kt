package com.system.location.service.backend

import com.system.location.service.backend.mock.*
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.location.LocationSample
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GmsMockSessionTest {
    /** Only the external Task completion is controlled; lifecycle logic is production code. */
    private class Operation(completed: Boolean = false) : GmsOperation {
        var deferCallbacks = false
        private var complete = completed
        private var error: Exception? = null
        private val listeners = mutableListOf<() -> Unit>()
        override val isComplete get() = complete
        override val isSuccessful get() = complete && error == null
        override val failure get() = error
        fun finish(failure: Exception? = null) {
            check(!complete)
            error = failure; complete = true
            if (!deferCallbacks) releaseCallbacks()
        }
        fun releaseCallbacks() { listeners.toList().also { listeners.clear() }.forEach { it() } }
        override fun then(action: () -> GmsOperation?): GmsOperation {
            var next: GmsOperation? = null
            val callback = { next = action() ?: Operation(true); Unit }
            if (complete) callback() else listeners += callback
            return object : GmsOperation {
                override val isComplete get() = next?.isComplete == true
                override val isSuccessful get() = next?.isSuccessful == true
                override val failure get() = next?.failure
                override fun then(action: () -> GmsOperation?): GmsOperation = error("unused")
            }
        }
    }
    private class Ownership : GmsOwnership {
        override var pending = false
        override fun record(pending: Boolean) { this.pending = pending }
    }
    private class Port {
        val ownership = Ownership()
        val modes = mutableListOf<Boolean>()
        var enable = Operation(true)
        var disable: () -> Operation = { Operation(true) }
        var publication = Operation()
        var time = 0L
        fun session() = GmsMockSession(true, { true }, ownership, { mode ->
            modes += mode
            if (mode) enable else disable()
        }, { publication }, { time }, 20)
    }
    private val sample = LocationSample(Wgs84(1.0, 2.0), 10.0, 5f, 0f, 0f, 1, 1)

    @Test fun deferredEnableContinuationBlocksReplacementUntilCompensationCompletes() = runBlocking {
        val port = Port().apply { enable = Operation().apply { deferCallbacks = true } }
        val oldEnable = port.enable
        val session = port.session()
        assertTrue(runCatching { session.start() }.isFailure)
        assertTrue(runCatching { session.stop() }.isFailure)
        oldEnable.finish()
        repeat(2) { assertTrue(runCatching { session.stop() }.isFailure) }
        val before = port.modes.toList()
        assertTrue(runCatching { session.start() }.isFailure)
        assertEquals(before, port.modes)
        assertTrue(port.ownership.pending)
        oldEnable.releaseCallbacks()
        session.stop()
        assertFalse(port.ownership.pending)
        port.enable = Operation(true)
        session.start()
        assertEquals(com.system.location.service.core.backend.Availability.AVAILABLE, session.capability.availability)
    }

    @Test fun failedLateCompensationKeepsOwnershipUntilFreshDisableSucceeds() = runBlocking {
        val port = Port().apply { enable = Operation() }
        val session = port.session()
        assertTrue(runCatching { session.start() }.isFailure)
        assertTrue(runCatching { session.stop() }.isFailure)
        val lateDisable = Operation()
        port.disable = { lateDisable }
        port.enable.finish()
        assertTrue(runCatching { session.stop() }.isFailure)
        assertTrue(port.ownership.pending)
        port.disable = { Operation(true) }
        assertTrue(runCatching { session.stop() }.isFailure)
        assertTrue(port.ownership.pending)
        val before = port.modes.toList()
        assertTrue(runCatching { session.start() }.isFailure)
        assertEquals(before, port.modes)
        lateDisable.finish(IllegalStateException("obsolete compensation"))
        session.stop()
        assertFalse(port.ownership.pending)
        session.start()
        assertEquals(com.system.location.service.core.backend.Availability.AVAILABLE, session.capability.availability)
    }

    @Test fun lateSuccessfulCompensationBlocksReplacementUntilItFinishes() = runBlocking {
        val port = Port().apply { enable = Operation() }
        val session = port.session()
        assertTrue(runCatching { session.start() }.isFailure)
        val lateDisable = Operation()
        port.disable = { lateDisable }
        port.enable.finish()
        // Subsequent stop attempts have their own successful disable Tasks.
        port.disable = { Operation(true) }
        repeat(3) { assertTrue(runCatching { session.stop() }.isFailure) }
        assertTrue(port.ownership.pending)
        val before = port.modes.toList()
        assertTrue(runCatching { session.start() }.isFailure)
        assertEquals(before, port.modes)
        lateDisable.finish()
        session.stop()
        assertFalse(port.ownership.pending)
        session.start()
        assertEquals(com.system.location.service.core.backend.Availability.AVAILABLE, session.capability.availability)
    }

    @Test fun unfinishedCleanupBlocksNewEnableUntilLateEnableCompensates() = runBlocking {
        val port = Port().apply { enable = Operation() }
        val session = port.session()
        assertTrue(runCatching { session.start() }.isFailure)
        assertTrue(runCatching { session.stop() }.isFailure)
        val calls = port.modes.toList()
        assertTrue(runCatching { session.start() }.isFailure)
        assertEquals(calls, port.modes)
        port.enable.finish()
        session.stop()
        session.start()
        assertEquals(com.system.location.service.core.backend.Availability.AVAILABLE, session.capability.availability)
    }

    @Test fun pendingPublicationDegradesThroughRealFusedBackendAndDisablesGms() = runBlocking {
        val port = Port(); val session = port.session()
        var basePublishes = 0
        val base = object : com.system.location.service.core.backend.LocationBackend {
            override val type = com.system.location.service.core.backend.BackendType.MOCK_PROVIDER
            override val capabilities = emptyMap<com.system.location.service.core.backend.Capability, com.system.location.service.core.backend.CapabilityStatus>()
            override suspend fun prepare() = com.system.location.service.core.backend.BackendResult.Success
            override suspend fun start() = com.system.location.service.core.backend.BackendResult.Success
            override suspend fun publish(sample: LocationSample) = com.system.location.service.core.backend.BackendResult.Success.also { basePublishes++ }
            override suspend fun stop() = com.system.location.service.core.backend.BackendResult.Success
            override suspend fun release() = stop()
        }
        val backend = FusedMockBackend(base, session)
        backend.prepare(); backend.start(); backend.publish(sample)
        port.time = 3_000_000_000
        assertEquals(com.system.location.service.core.backend.BackendResult.Success, backend.publish(sample))
        assertEquals(2, basePublishes)
        assertEquals(listOf(true, false), port.modes)
        assertFalse(port.ownership.pending)
        assertTrue(backend.diagnose().any { it.stage == "GMS_DEGRADED" })
    }

    @Test fun recreatedSessionCleansRetainedOwnershipBeforeStarting() = runBlocking {
        val port = Port(); val session = port.session()
        val failedDisable = Operation()
        session.start(); port.disable = { failedDisable }
        assertTrue(runCatching { session.stop() }.isFailure)
        failedDisable.finish(IllegalStateException("previous cleanup failed"))
        port.disable = { Operation(true) }
        val recovered = port.session()
        recovered.prepare()
        assertFalse(port.ownership.pending)
        recovered.start()
        assertEquals(listOf(true, false, false, true), port.modes)
    }

    @Test fun latePublicationFailureCannotReplaceNewSessionSubmission() = runBlocking {
        val port = Port(); val session = port.session()
        session.start(); session.publish(sample)
        val oldPublication = port.publication
        session.stop()
        port.publication = Operation(true)
        session.start(); session.publish(sample)
        oldPublication.finish(IllegalStateException("old failure"))
        session.publish(sample)
        assertTrue(port.ownership.pending)
        assertEquals(com.system.location.service.core.backend.Availability.AVAILABLE, session.capability.availability)
    }

    @Test fun pendingEnableGetsImmediateDisableAndLateCompletionCompensation() = runBlocking {
        val port = Port().apply { enable = Operation() }
        val session = port.session()
        assertTrue(runCatching { session.start() }.isFailure)
        assertTrue(runCatching { session.stop() }.isFailure)
        assertEquals(listOf(true, false), port.modes)
        assertTrue(port.ownership.pending)
        port.enable.finish()
        assertEquals(listOf(true, false, false), port.modes)
        session.stop()
        assertFalse(port.ownership.pending)
        session.start()
        port.publication.finish(IllegalStateException("old publication"))
        assertEquals(com.system.location.service.core.backend.Availability.AVAILABLE, session.capability.availability)
    }

    @Test fun timedOutDisablesAreRetriedButAllMustFinishBeforeNewEnable() = runBlocking {
        listOf<Exception?>(null, IllegalStateException("late failure")).forEach { lateFailure ->
            val port = Port(); val session = port.session()
            val firstDisable = Operation()
            val secondDisable = Operation()
            port.disable = { firstDisable }
            session.start(); session.publish(sample)
            assertTrue(runCatching { session.stop() }.isFailure)
            port.disable = { secondDisable }
            assertTrue(runCatching { session.stop() }.isFailure)
            port.disable = { Operation(true) }
            assertTrue(runCatching { session.stop() }.isFailure)
            assertEquals(listOf(true, false, false, false), port.modes)
            assertTrue(port.ownership.pending)
            assertFalse(port.publication.isComplete)
            val before = port.modes.toList()
            assertTrue(runCatching { session.start() }.isFailure)
            assertEquals(before, port.modes)
            secondDisable.finish()
            assertTrue(runCatching { session.stop() }.isFailure)
            firstDisable.finish(lateFailure)
            session.stop()
            assertFalse(port.ownership.pending)
            session.start()
            assertEquals(com.system.location.service.core.backend.Availability.AVAILABLE, session.capability.availability)
        }
    }

    @Test fun permanentlyPendingPublicationDoesNotBlockDisable() = runBlocking {
        val port = Port(); val session = port.session()
        session.start(); session.publish(sample)
        runCatching { session.stop() }
        assertEquals(listOf(true, false), port.modes)
        assertFalse(port.ownership.pending)
        assertFalse(port.publication.isComplete)
    }
}
