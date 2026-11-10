package com.system.location.service.gnss

import org.junit.Assert.*
import org.junit.Test

class GnssCaptureSessionGuardTest {
    private val stopped = GnssCaptureSystemState("system-instance", 12, false)
    private fun guard(remote: GnssCaptureSystemState? = stopped, required: Boolean = true) =
        GnssCaptureSessionGuard(7, false, required, remote)
    private fun rejects(block: () -> Unit) {
        assertThrows(IllegalStateException::class.java) { block() }
    }

    @Test fun knownStoppedStateIsRequiredForHookBackendsAndActiveStatesAreRejected() {
        rejects { guard(null) }
        rejects { guard(stopped.copy(active = true)) }
        rejects { GnssCaptureSessionGuard(7, true, true, stopped) }
        val session = guard()
        session.checkLocal(7, false)
        session.checkSystem(stopped)
    }

    @Test fun aCompleteLocalStartStopBetweenPollsCannotBeAcceptedAsAnUnchangedWindow() {
        rejects { guard().checkLocal(8, false) }
        rejects { guard().checkLocal(7, true) }
    }

    @Test fun remoteStartStopRestartAndLostAuthorityAllDiscardTheWindow() {
        val session = guard()
        rejects { session.checkSystem(stopped.copy(generation = 14)) }
        rejects { session.checkSystem(stopped.copy(active = true)) }
        rejects { session.checkSystem(stopped.copy(instance = "reloaded-system-instance")) }
        rejects { session.checkSystem(null) }
    }

    @Test fun standardProviderCanRunWithoutAHookButAuthorityCannotChangeMidCapture() {
        val session = guard(null, required = false)
        session.checkSystem(null)
        session.checkLocal(7, false)
        rejects { session.checkSystem(stopped) }
        // Selecting the standard backend does not excuse an observable active Hook.
        rejects { guard(stopped.copy(active = true), required = false) }
    }
}
