package io.github.lecheeel.integrity.core

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class IntegrityAnalyzerTest {
    private fun sample(t: Long = 1, lat: Double = 30.0, provider: String = "gps", mock: Boolean = false,
        cached: Boolean = false, precise: Boolean = true, age: Long = 0) = LocationObservation(provider, lat, 120.0,
        5f, null, null, null, t * 1000, t * 1_000_000_000, (t + age) * 1_000_000_000,
        mock, cached, precise)
    @Test fun noSamplesIsInsufficient() { assertEquals("INSUFFICIENT", IntegrityAnalyzer().verdict()) }
    @Test fun mockIsDirectEvidence() {
        val engine = IntegrityAnalyzer(); engine.observe(sample(mock = true))
        assertEquals("MOCK_OBSERVED", engine.verdict()); assertEquals("DIRECT", engine.evidence().single().level)
    }
    @Test fun normalIsNotAnAuthenticityClaim() {
        val engine = IntegrityAnalyzer(); engine.observe(sample())
        assertEquals("NO_EXPLICIT_EVIDENCE", engine.verdict())
    }
    @Test fun cachedOnlyDoesNotCountAsLive() {
        val engine = IntegrityAnalyzer(); engine.observe(sample(cached = true, age = 100))
        assertEquals(0L, engine.liveSamples); assertEquals("INSUFFICIENT", engine.verdict())
    }
    @Test fun staleSampleIsCaution() {
        val engine = IntegrityAnalyzer(); engine.observe(sample(age = 11))
        assertTrue(engine.evidence().any { it.code == "STALE" }); assertEquals("ANOMALIES_OBSERVED", engine.verdict())
    }
    @Test fun futureSampleIsCaution() {
        val engine = IntegrityAnalyzer(); engine.observe(sample(age = -2))
        assertTrue(engine.evidence().any { it.code == "FUTURE" })
    }
    @Test fun backwardsSampleIsNotSilentlyAccepted() {
        val engine = IntegrityAnalyzer(); engine.observe(sample(5)); engine.observe(sample(4))
        assertTrue(engine.evidence().any { it.code == "TIME_REVERSED" })
    }
    @Test fun cachedHistoryDoesNotTriggerTimeReversal() {
        val engine = IntegrityAnalyzer(); engine.observe(sample(5)); engine.observe(sample(1, cached = true))
        assertFalse(engine.evidence().any { it.code == "TIME_REVERSED" })
    }
    @Test fun repeatedCoordinatesAreInformational() {
        val engine = IntegrityAnalyzer(); engine.observe(sample()); engine.observe(sample(2))
        assertEquals("INFO", engine.evidence().single().level); assertEquals("NO_EXPLICIT_EVIDENCE", engine.verdict())
    }
    @Test fun largeJumpIsReportedWithPrecisePermission() {
        val engine = IntegrityAnalyzer(); engine.observe(sample()); engine.observe(sample(2, lat = 40.0))
        assertTrue(engine.evidence().any { it.code == "LARGE_JUMP" })
    }
    @Test fun approximatePermissionDisablesMovementInference() {
        val engine = IntegrityAnalyzer(); engine.observe(sample(precise = false)); engine.observe(sample(2, lat = 40.0, precise = false))
        assertTrue(engine.evidence().isEmpty())
    }
    @Test fun sourceDisagreementIsOnlyCaution() {
        val engine = IntegrityAnalyzer(); engine.observe(sample()); engine.observe(sample(lat = 40.0, provider = "network"))
        assertEquals("CAUTION", engine.evidence().single().level)
    }
    @Test fun samplesAreBoundedAndCountsAreLifetime() {
        val engine = IntegrityAnalyzer(3); (1L..10L).forEach { engine.observe(sample(it)) }
        assertEquals(3, engine.samples().size); assertEquals(10L, engine.totalSamples); assertEquals(8_000L, engine.samples().first().wallTimeMillis)
    }
    @Test fun intervalsAndPercentilesArePerProvider() {
        val engine = IntegrityAnalyzer(); engine.observe(sample(1)); engine.observe(sample(2)); engine.observe(sample(5))
        engine.observe(sample(10, provider = "network"))
        val gps = engine.sources().first { it.provider == "gps" }
        assertEquals(1000.0, gps.intervalP50Millis!!, 0.0); assertEquals(3000.0, gps.intervalP95Millis!!, 0.0)
        assertNull(engine.sources().first { it.provider == "network" }.intervalP95Millis)
    }
    @Test fun zeroLongitudeAndDatelineDistanceAreValid() {
        val a = sample().copy(longitude = 179.999); val b = a.copy(longitude = -179.999)
        assertTrue(IntegrityAnalyzer.distanceMeters(a, b) in 100.0..300.0)
        assertEquals(0.0, IntegrityAnalyzer.distanceMeters(a, a), 0.0)
    }
    @Test fun intervalRetentionIsBounded() {
        val engine = IntegrityAnalyzer(); (1L..1200L).forEach { engine.observe(sample(it)) }
        assertEquals(1000, engine.sources().single().retainedIntervals)
    }
    @Test fun nonFiniteCoordinatesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { sample(lat = Double.NaN) }
    }
    @Test fun observationsRoundTripWithoutPrecisionLoss() {
        val original = sample().copy(latitude = 31.123456789123, altitudeMeters = 42.123)
        val json = Json.encodeToString(LocationObservation.serializer(), original)
        assertEquals(original, Json.decodeFromString(LocationObservation.serializer(), json))
    }
    @Test fun passiveAndGpsSubscriptionsDoNotMixIntervalStatistics() {
        val engine = IntegrityAnalyzer()
        engine.observe(sample(1).copy(requestedProvider = "gps"))
        engine.observe(sample(1).copy(requestedProvider = "passive"))
        engine.observe(sample(2).copy(requestedProvider = "gps"))
        assertEquals(2, engine.sources().size)
        assertEquals(1000.0, engine.sources().first { it.provider == "gps" }.intervalP95Millis!!, 0.0)
        assertNull(engine.sources().first { it.provider == "passive" }.intervalP95Millis)
    }
}
