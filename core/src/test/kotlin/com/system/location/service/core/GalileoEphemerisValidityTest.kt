package com.system.location.service.core

import com.system.location.service.core.gnss.*
import org.junit.Assert.*
import org.junit.Test

class GalileoEphemerisValidityTest {
    @Test fun standardAgeIsForwardFromToeAndNeverCenteredOnToeOrRenewedByUse() {
        val toe = GalileoTime(1000, 432000.0)
        val interval = GalileoEphemerisValidity.resolve(toe)!!
        assertEquals(toe, interval.from)
        assertEquals(GalileoTime(1000, 446400.0), interval.until)
        assertFalse(interval.contains(toe.plusSeconds(-0.001)))
        assertTrue(interval.contains(toe))
        assertTrue(interval.contains(toe.plusSeconds(14400.0)))
        assertFalse(interval.contains(toe.plusSeconds(14400.001)))
        assertFalse(interval.contains(toe.plusSeconds(604800.0)))
    }

    @Test fun fullWeekCrossingAndIndependentLimitsCannotExtendAge() {
        val toe = GalileoTime(1000, 604740.0)
        val interval = GalileoEphemerisValidity.resolve(toe,
            toe.plusSeconds(-7200.0), toe.plusSeconds(100000.0))!!
        assertEquals(toe, interval.from)
        assertEquals(GalileoTime(1001, 14340.0), interval.until)
        val shorter = GalileoEphemerisValidity.resolve(toe, toe, toe.plusSeconds(60.0))!!
        assertEquals(GalileoTime(1001, 0.0), shorter.until)
        assertNull(GalileoEphemerisValidity.resolve(toe, toe.plusSeconds(14401.0), toe.plusSeconds(14402.0)))
        assertNull(GalileoEphemerisValidity.resolve(toe, toe.plusSeconds(-2.0), toe.plusSeconds(-1.0)))
        assertNull(GalileoEphemerisValidity.resolve(toe, suppliedFrom = toe))
        assertNull(GalileoEphemerisValidity.resolve(GalileoTime(Int.MAX_VALUE, 604799.0)))
    }
}
