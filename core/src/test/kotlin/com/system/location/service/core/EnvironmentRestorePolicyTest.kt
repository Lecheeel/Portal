package com.system.location.service.core

import com.system.location.service.core.environment.EnvironmentRestorePolicy
import com.system.location.service.core.environment.EnvironmentSourceType
import org.junit.Assert.assertEquals
import org.junit.Test

class EnvironmentRestorePolicyTest {
    @Test fun replayRequiresProfileJson() {
        assertEquals("REPLAY", EnvironmentRestorePolicy.sourceMode(EnvironmentSourceType.REPLAY, "{}"))
        assertEquals("PROCEDURAL", EnvironmentRestorePolicy.sourceMode(EnvironmentSourceType.REPLAY, null))
        assertEquals("PROCEDURAL", EnvironmentRestorePolicy.sourceMode(EnvironmentSourceType.REPLAY, " "))
    }

    @Test fun proceduralNeverBecomesReplay() {
        assertEquals("PROCEDURAL", EnvironmentRestorePolicy.sourceMode(EnvironmentSourceType.PROCEDURAL, "{}"))
    }
}
