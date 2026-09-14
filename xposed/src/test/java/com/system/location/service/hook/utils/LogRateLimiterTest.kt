package com.system.location.service.hook.utils

import org.junit.Assert.*
import org.junit.Test

class LogRateLimiterTest {
    @Test fun duplicatesAndChangingMessagesAreBothBounded() {
        val limiter = LogRateLimiter(maxPerSecond = 3)
        assertTrue(limiter.allow("one", 0))
        assertFalse(limiter.allow("one", 1))
        assertTrue(limiter.allow("two", 2)); assertTrue(limiter.allow("three", 3))
        assertFalse(limiter.allow("four", 4))
        assertTrue(limiter.allow("four", 1_000_000_000))
        assertFalse(limiter.allow("one", 2_000_000_000))
        assertTrue(limiter.allow("one", 5_000_000_000))
    }
}
