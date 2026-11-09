package com.system.location.service.core

import com.system.location.service.core.environment.EnvironmentRestorePolicy
import com.system.location.service.core.environment.EnvironmentSourceType
import com.system.location.service.core.environment.EnvironmentSourceMode
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

    @Test fun cachedReplayDoesNotOverrideExplicitProceduralChoiceWhenSavingAndRestoring() {
        val source = EnvironmentRestorePolicy.savedSource(EnvironmentSourceMode.PROCEDURAL, "cached-profile")
        assertEquals(EnvironmentSourceType.PROCEDURAL, source)
        assertEquals("PROCEDURAL", EnvironmentRestorePolicy.sourceMode(source, "cached-profile"))
    }

    @Test fun autoUsesAvailableReplayButMissingReplayFallsBackToProcedural() {
        assertEquals(EnvironmentSourceType.REPLAY, EnvironmentRestorePolicy.savedSource(EnvironmentSourceMode.AUTO, "profile"))
        assertEquals(EnvironmentSourceType.REPLAY, EnvironmentRestorePolicy.savedSource(EnvironmentSourceMode.REPLAY, "profile"))
        assertEquals(EnvironmentSourceType.PROCEDURAL, EnvironmentRestorePolicy.savedSource(EnvironmentSourceMode.AUTO, ""))
        assertEquals(EnvironmentSourceType.PROCEDURAL, EnvironmentRestorePolicy.savedSource(EnvironmentSourceMode.REPLAY, null))
    }
}
