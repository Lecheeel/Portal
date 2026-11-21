package com.system.location.service.hook.runtime

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class CurrentLocationDeliveryTest {
    @Test fun samplesAreConfinedToTheActiveProviderAndThread() {
        val owner = Any()
        val pool = Executors.newSingleThreadExecutor()
        try {
            assertFalse(CurrentLocationDelivery.active)
            CurrentLocationDelivery.during(owner, { "mock" }) {
                assertTrue(CurrentLocationDelivery.active)
                assertEquals("mock", CurrentLocationDelivery.sampleFor(owner))
                assertNull(CurrentLocationDelivery.sampleFor(Any()))
                assertFalse(pool.submit<Boolean> { CurrentLocationDelivery.active }.get(5, TimeUnit.SECONDS))
            }
            assertFalse(CurrentLocationDelivery.active)
            assertNull(CurrentLocationDelivery.sampleFor(owner))
        } finally { pool.shutdownNow() }
    }

    @Test fun nestedDeliveryRestoresItsParentEvenWhenTheFrameworkThrows() {
        val owner = Any()
        val nested = Any()
        CurrentLocationDelivery.during(owner, { "outer" }) {
            val failure = runCatching {
                CurrentLocationDelivery.during(nested, { "inner" }) {
                    assertNull(CurrentLocationDelivery.sampleFor(owner))
                    assertEquals("inner", CurrentLocationDelivery.sampleFor(nested))
                    error("framework failure")
                }
            }.exceptionOrNull()
            assertEquals("framework failure", failure?.message)
            assertEquals("outer", CurrentLocationDelivery.sampleFor(owner))
        }
        assertFalse(CurrentLocationDelivery.active)
    }

    @Test fun sampleAvailabilityIsRecheckedAtReadTime() {
        val owner = Any()
        var enabled = true
        CurrentLocationDelivery.during(owner, { if (enabled) "mock" else null }) {
            assertEquals("mock", CurrentLocationDelivery.sampleFor(owner))
            enabled = false
            assertNull(CurrentLocationDelivery.sampleFor(owner))
        }
    }
}
