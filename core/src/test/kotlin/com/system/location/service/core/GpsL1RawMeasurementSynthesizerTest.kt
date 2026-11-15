package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import java.time.Instant
import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsL1RawMeasurementSynthesizerTest {
    private val navigation = RinexNavigationReader.read(
        requireNotNull(javaClass.getResource("/gnss/bkg-2026315-four-system.rnx")).readText(),
    )
    private val metadata = Json.parseToJsonElement(
        requireNotNull(javaClass.getResource("/gnss/bkg-2026315-four-system-source.json")).readText(),
    ).jsonObject
    private val epochMillis = Instant.parse(metadata.getValue("evaluationUtc").jsonPrimitive.content).toEpochMilli()
    private val utc = GnssUtcTimeScale.parseIers(
        requireNotNull(javaClass.getResource("/gnss/leap-seconds.list")).readText(),
    )
    private val referenceEpoch = requireNotNull(utc.gpsAtUtcMillis(epochMillis).time)
    private val ephemerides = navigation.records.filter { it.system == RinexNavigationSystem.GPS }
        .mapNotNull { runCatching { RinexGpsImporter.decode(it).ephemeris }.getOrNull() }
        .filter { it.health == 0 }
        .filter { referenceEpoch.secondsSince(it.validFrom) >= 0 && it.validUntil.secondsSince(referenceEpoch) >= 0 }
        .distinctBy { it.svid }
    private val context = findObservableContext()
    private val epoch = context.first
    private val target = context.second
    private val biasSeconds = 250e-6
    private val drift = 3e-10

    private fun capturedAt(time: GpsTime) =
        epochMillis + (time.secondsSince(referenceEpoch) * 1_000.0).toLong()

    private fun findObservableContext(): Pair<GpsTime, Wgs84> {
        val points = (-80..80 step 10).flatMap { latitude ->
            (-180..160 step 20).map { longitude -> Wgs84(latitude.toDouble(), longitude.toDouble()) }
        }
        for (offset in -3_600..3_600 step 300) {
            val time = referenceEpoch.plusSeconds(offset.toDouble())
            points.firstOrNull { point ->
                ephemerides.count { ephemeris ->
                    GpsReceptionGeometry.observe(ephemeris, time, point, 18.0).observation
                        ?.lineOfSight?.aboveHorizon == true
                } >= GpsL1RawMeasurementSynthesizer.MIN_MEASUREMENTS
            }?.let { return time to it }
        }
        error("Bundled RINEX GPS subset has no four-satellite target geometry")
    }

    private fun receiverAt(time: GpsTime) = GpsL1ReceiverState(
        observer = target,
        ellipsoidHeightMeters = 18.0,
        clockBiasSeconds = biasSeconds + drift * time.secondsSince(epoch),
        clockDriftSecondsPerSecond = drift,
        ionosphericDelayMeters = 4.0,
        troposphericDelayMeters = 2.5,
        clockUncertaintyNanos = 30.0,
        clockBiasUncertaintyNanos = 10.0,
        clockDriftUncertaintyNanosPerSecond = 0.2,
        elapsedRealtimeUncertaintyNanos = 20.0,
    )

    private fun synthesizer(ephemerides: List<GpsLnavEphemeris> = this.ephemerides) =
        GpsL1RawMeasurementSynthesizer(ephemerides, ::receiverAt) { _, _ ->
            GpsL1SignalQuality(45.0, 25L, 0.2, 0.03)
        }

    @Test
    fun generatedClockAndCodeTimesResolveToTargetPseudoranges() {
        val batch = synthesizer().synthesize(epoch, 100_000_000_000L, capturedAt(epoch), 0)
        assertNotNull(batch)
        val decodedClock = GpsReceiverClock.decode(batch!!).epoch
        assertNotNull(decodedClock)
        assertEquals(biasSeconds * 1e9, decodedClock!!.time.secondsSince(epoch) * 1e9, 1.0)
        assertEquals(30.0, batch.timeUncertaintyNanos!!, 0.0)
        assertEquals(10.0, batch.biasUncertaintyNanos!!, 0.0)
        assertEquals(0.2, batch.driftUncertaintyNanosPerSecond!!, 0.0)
        assertEquals(20.0, batch.elapsedRealtimeUncertaintyNanos!!, 0.0)
        assertTrue(batch.measurements.size >= GpsL1RawMeasurementSynthesizer.MIN_MEASUREMENTS)

        batch.measurements.forEach { measurement ->
            assertEquals(GpsL1RawMeasurementSynthesizer.GPS_L1_CA_HZ, measurement.carrierHz!!, 0.0)
            assertEquals(GpsL1RawMeasurementSynthesizer.GPS_L1_CA_CODE_TYPE, measurement.codeType)
            assertEquals(1 shl 14 or 1, measurement.state)
            assertEquals(3, measurement.accumulatedDeltaRangeState)
            assertEquals(0.0, measurement.accumulatedDeltaRangeMeters, 0.0)
            assertTrue(measurement.receivedSvTimeNanos in 0 until GpsTime.SECONDS_PER_WEEK.toLong() * 1_000_000_000L)

            val receiverTime = decodedClock.time.secondsOfWeek
            var travelSeconds = receiverTime - measurement.receivedSvTimeNanos / 1e9
            if (travelSeconds < 0) travelSeconds += GpsTime.SECONDS_PER_WEEK
            val pseudorange = travelSeconds * GpsReceptionGeometry.SPEED_OF_LIGHT_METERS_PER_SECOND
            val ephemeris = ephemerides.single { it.svid == measurement.svid }
            val geometry = GpsReceptionGeometry.observe(ephemeris, epoch, target, 18.0).observation!!
            val state = receiverAt(epoch)
            val expected = geometry.lineOfSight.rangeMeters +
                GpsReceptionGeometry.SPEED_OF_LIGHT_METERS_PER_SECOND *
                (state.clockBiasSeconds - geometry.l1CaClockBiasSeconds) +
                state.ionosphericDelayMeters + state.troposphericDelayMeters
            assertEquals(expected, pseudorange, 0.20)
        }
    }

    @Test
    fun measurementRateAndAccumulatedPhaseFollowTheSameClockAndTargetTimeline() {
        val model = synthesizer()
        val first = model.synthesize(epoch, 100_000_000_000L, capturedAt(epoch), 0)!!
        val nextTime = epoch.plusSeconds(0.5)
        val second = model.synthesize(nextTime, 100_500_000_000L, capturedAt(nextTime), 0)!!
        val firstIds = first.measurements.map { it.svid }.toSet()

        second.measurements.forEach { current ->
            if (current.svid !in firstIds) return@forEach
            assertEquals(1, current.accumulatedDeltaRangeState)
            assertTrue(abs(current.pseudorangeRateMetersPerSecond) < 2_000.0)
            val ephemeris = ephemerides.single { it.svid == current.svid }
            val before = GpsReceptionGeometry.observe(ephemeris, nextTime.plusSeconds(-0.05), target, 18.0).observation!!
            val after = GpsReceptionGeometry.observe(ephemeris, nextTime.plusSeconds(0.05), target, 18.0).observation!!
            val beforeState = receiverAt(nextTime.plusSeconds(-0.05))
            val afterState = receiverAt(nextTime.plusSeconds(0.05))
            val beforeCode = before.lineOfSight.rangeMeters +
                GpsReceptionGeometry.SPEED_OF_LIGHT_METERS_PER_SECOND *
                (beforeState.clockBiasSeconds - before.l1CaClockBiasSeconds) + 6.5
            val afterCode = after.lineOfSight.rangeMeters +
                GpsReceptionGeometry.SPEED_OF_LIGHT_METERS_PER_SECOND *
                (afterState.clockBiasSeconds - after.l1CaClockBiasSeconds) + 6.5
            assertEquals((afterCode - beforeCode) / 0.1, current.pseudorangeRateMetersPerSecond, 1e-6)

            val firstGeometry = GpsReceptionGeometry.observe(ephemeris, epoch, target, 18.0).observation!!
            val currentGeometry = GpsReceptionGeometry.observe(ephemeris, nextTime, target, 18.0).observation!!
            val firstPhase = firstGeometry.lineOfSight.rangeMeters +
                GpsReceptionGeometry.SPEED_OF_LIGHT_METERS_PER_SECOND *
                (receiverAt(epoch).clockBiasSeconds - firstGeometry.clockBiasSeconds) + 6.5
            val currentPhase = currentGeometry.lineOfSight.rangeMeters +
                GpsReceptionGeometry.SPEED_OF_LIGHT_METERS_PER_SECOND *
                (receiverAt(nextTime).clockBiasSeconds - currentGeometry.clockBiasSeconds) + 6.5
            assertEquals(currentPhase - firstPhase, current.accumulatedDeltaRangeMeters, 0.20)
        }
    }

    @Test
    fun insufficientGpsAndNonMonotonicTimeDoNotProduceOrAdvanceFrames() {
        assertNull(synthesizer(ephemerides.take(3)).synthesize(epoch, 100_000_000_000L, capturedAt(epoch), 0))
        val model = synthesizer()
        assertNotNull(model.synthesize(epoch, 100_000_000_000L, capturedAt(epoch), 0))
        assertNull(model.synthesize(epoch, 100_000_000_001L, capturedAt(epoch), 0))
        val next = epoch.plusSeconds(0.5)
        assertNotNull(model.synthesize(next, 100_500_000_000L, capturedAt(next), 0))
        val stale = epoch.plusSeconds(0.25)
        assertNull(model.synthesize(stale, 100_750_000_000L, capturedAt(stale), 0))
    }
}
