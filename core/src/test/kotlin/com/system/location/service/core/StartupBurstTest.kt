package com.system.location.service.core

import com.system.location.service.core.runtime.*
import org.junit.Assert.*
import org.junit.Test

class StartupBurstTest {
    private val running = RuntimeState(phase = RuntimePhase.RUNNING, scenarioId = "a", startedAt = 1)
    @Test fun exactlyFiveTicksThenNormalCadence() {
        val burst = StartupBurst("a", 1)
        repeat(5) { assertEquals(80L, burst.nextDelay(running, 100)); assertTrue(burst.accept(running)) }
        assertEquals(100L, burst.nextDelay(running, 100)); assertFalse(burst.accept(running))
    }
    @Test fun pauseStopOrReplacementInvalidateRemainingTicks() {
        for (changed in listOf(running.copy(phase = RuntimePhase.PAUSED), running.copy(phase = RuntimePhase.STOPPED), running.copy(scenarioId = "b"), running.copy(startedAt = 2))) {
            val burst = StartupBurst("a", 1)
            assertFalse(burst.accept(changed)); assertEquals(500L, burst.nextDelay(running, 500))
        }
    }
}
