package com.system.location.service.core

import com.system.location.service.core.gnss.BundledGnssFallback
import com.system.location.service.core.gnss.GnssBundledOrbitResources
import com.system.location.service.core.gnss.RawGnssSynthesis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawGnssSynthesisTest {
    @Test fun bundledAlmanacPromotesToUsableGpsEphemerides() {
        val almanac = requireNotNull(GnssBundledOrbitResources.gpsAlmanac())
        val reference = almanac.first().referenceTime
        val now = requireNotNull(GnssBundledOrbitResources.utcTimeScale())
            .utcAtGpsTime(reference).utcMillis
        val ephemerides = RawGnssSynthesis.ephemerides(null, requireNotNull(now))
        assertTrue("Expected a usable GPS constellation", ephemerides.size >= 4)
        assertTrue("Every ephemeris must be healthy", ephemerides.all { it.health == 0 })
        assertEquals(ephemerides.size, ephemerides.map { it.svid }.distinct().size)
        assertTrue(ephemerides.all { it.sqrtSemiMajorAxis in 2530.0..8192.0 })
    }

    @Test fun embeddedFallbackMatchesBundledResources() {
        val virtual = requireNotNull(BundledGnssFallback.read("virtual-walker.json"))
        val utc = requireNotNull(BundledGnssFallback.read("leap-seconds.list"))
        val yuma = requireNotNull(BundledGnssFallback.read("gps-yuma.json"))
        assertTrue(virtual.startsWith("{"))
        assertTrue(utc.length > 100)
        assertTrue(yuma.contains("almanac"))
    }

    @Test fun malformedOrExpiredTimeDoesNotInventEphemerides() {
        assertTrue(RawGnssSynthesis.ephemerides(null, Long.MAX_VALUE).isEmpty())
    }
}
