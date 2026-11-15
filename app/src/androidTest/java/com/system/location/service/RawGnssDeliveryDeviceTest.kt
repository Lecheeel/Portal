package com.system.location.service

import android.location.GnssMeasurement
import android.location.GnssMeasurementsEvent
import android.location.LocationManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.core.backend.BackendType
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.Constellation
import com.system.location.service.core.gnss.GnssMeasurementBatch
import com.system.location.service.core.gnss.GnssMeasurementObservation
import com.system.location.service.core.gnss.GnssObserverGeometry
import com.system.location.service.core.gnss.GpsIndependentPositionSolver
import com.system.location.service.core.gnss.GpsRangeObservation
import com.system.location.service.core.gnss.GpsReceptionGeometry
import com.system.location.service.core.gnss.GpsReceiverClock
import com.system.location.service.core.gnss.GpsTime
import com.system.location.service.core.gnss.RawGnssSynthesis
import com.system.location.service.core.scenario.MovementProfile
import com.system.location.service.core.scenario.Scenario
import com.system.location.service.runtime.ScenarioRuntime
import kotlin.math.hypot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Device proof that the system-server hook delivers synthetic raw observations, not a rejection. */
@RunWith(AndroidJUnit4::class)
class RawGnssDeliveryDeviceTest {
    @Test fun syntheticGpsL1MeasurementsArriveFromTargetFrame() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(LocationManager::class.java)
        assumeTrue("Xposed backend unavailable", ScenarioRuntime.selectBackend(BackendType.XPOSED).await())
        val scenario = Scenario(
            id = "raw-canary",
            name = "Raw canary",
            point = Wgs84(31.2304, 121.4737),
            profile = MovementProfile(speedMps = 0.0, altitudeMeters = 12.0, accuracyMeters = 2.0f, intervalMs = 500L),
        )
        val started = ScenarioRuntime.start(scenario).await()
        assumeTrue("scenario did not start", started)
        val events = LinkedBlockingQueue<GnssMeasurementsEvent>()
        val statuses = LinkedBlockingQueue<Int>()
        val firstEvent = CountDownLatch(1)
        val callback = object : GnssMeasurementsEvent.Callback() {
            override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
                events.offer(event)
                firstEvent.countDown()
            }
            override fun onStatusChanged(status: Int) { statuses.offer(status) }
        }
        try {
            assertTrue(manager.registerGnssMeasurementsCallback({ it.run() }, callback))
            val status = statuses.poll(5, TimeUnit.SECONDS)
            assertEquals("Synthetic registration must report READY", 1, status)
            assertTrue("No synthetic raw measurement arrived", firstEvent.await(12, TimeUnit.SECONDS))
            val event = requireNotNull(events.poll())
            val clock = event.clock
            assertNotNull(clock)
            assertTrue("Synthetic clock must carry full bias", clock.hasFullBiasNanos())
            assertTrue("Synthetic clock must carry elapsed time", clock.hasElapsedRealtimeNanos())
            val measurements = event.measurements.toList()
            assertTrue("Need four GPS measurements, got ${measurements.size}", measurements.size >= 4)
            assertTrue(measurements.all { it.constellationType == 1 })
            assertTrue(measurements.all { it.hasCarrierFrequencyHz() })
            assertTrue(measurements.all {
                it.accumulatedDeltaRangeState and GnssMeasurement.ADR_STATE_VALID != 0
            })
            assertTrue(measurements.all { it.svid in 1..32 && it.receivedSvTimeNanos > 0 })
            assertTrue(measurements.all { it.cn0DbHz in 0.0..63.0 })
            assertEquals("Synthetic registration must report READY",
                GnssMeasurementsEvent.Callback.STATUS_READY, statuses.poll(2, TimeUnit.SECONDS))
            val statusAt = SystemClock.elapsedRealtimeNanos()
            assertTrue(clock.elapsedRealtimeNanos <= statusAt + 1_000_000_000L)

            // Independent receiver-side check: consume only the Android observations and solve
            // without reading the target coordinate from Portal's runtime state.
            val target = Wgs84(31.2304, 121.4737)
            val targetEcef = GnssObserverGeometry.ecef(target, 12.0)
            val ranges = syntheticRanges(event, target, 12.0)
            assertTrue("Need four range observations, got ${ranges.size}", ranges.size >= 4)
            val solved = GpsIndependentPositionSolver.solve(
                ranges,
                targetEcef.copy(
                    xMeters = targetEcef.xMeters + 1_000.0,
                    yMeters = targetEcef.yMeters - 700.0,
                    zMeters = targetEcef.zMeters + 400.0,
                ),
            )
            assertNotNull("Independent solver did not converge", solved)
            val error = hypot(hypot(
                solved!!.position.xMeters - targetEcef.xMeters,
                solved.position.yMeters - targetEcef.yMeters,
            ), solved.position.zMeters - targetEcef.zMeters)
            assertTrue("Synthetic raw solved ${error}m away from target", error < 30.0)
        } finally {
            manager.unregisterGnssMeasurementsCallback(callback)
            ScenarioRuntime.stop().await()
        }
    }

    private fun syntheticRanges(
        event: GnssMeasurementsEvent,
        target: Wgs84,
        altitudeMeters: Double,
    ): List<GpsRangeObservation> {
        val clock = event.clock
        val observations = event.measurements.map { measurement ->
            GnssMeasurementObservation(
                constellation = Constellation.GPS,
                svid = measurement.svid,
                state = measurement.state,
                timeOffsetNanos = measurement.timeOffsetNanos,
                receivedSvTimeNanos = measurement.receivedSvTimeNanos,
                receivedSvTimeUncertaintyNanos = measurement.receivedSvTimeUncertaintyNanos,
                cn0DbHz = measurement.cn0DbHz,
                pseudorangeRateMetersPerSecond = measurement.pseudorangeRateMetersPerSecond,
                pseudorangeRateUncertaintyMetersPerSecond =
                    measurement.pseudorangeRateUncertaintyMetersPerSecond,
                accumulatedDeltaRangeState = measurement.accumulatedDeltaRangeState,
                accumulatedDeltaRangeMeters = measurement.accumulatedDeltaRangeMeters,
                accumulatedDeltaRangeUncertaintyMeters =
                    measurement.accumulatedDeltaRangeUncertaintyMeters,
                carrierHz = measurement.carrierFrequencyHz.toDouble()
                    .takeIf { measurement.hasCarrierFrequencyHz() },
                codeType = measurement.codeType.takeIf { measurement.hasCodeType() },
            )
        }
        val batch = GnssMeasurementBatch(
            capturedAtMillis = System.currentTimeMillis(),
            timeNanos = clock.timeNanos,
            fullBiasNanos = clock.fullBiasNanos.takeIf { clock.hasFullBiasNanos() },
            biasNanos = clock.biasNanos.takeIf { clock.hasBiasNanos() },
            hardwareClockDiscontinuityCount = clock.hardwareClockDiscontinuityCount,
            measurements = observations,
            timeUncertaintyNanos = clock.timeUncertaintyNanos.takeIf { clock.hasTimeUncertaintyNanos() },
            biasUncertaintyNanos = clock.biasUncertaintyNanos.takeIf { clock.hasBiasUncertaintyNanos() },
            driftNanosPerSecond = clock.driftNanosPerSecond.takeIf { clock.hasDriftNanosPerSecond() },
            driftUncertaintyNanosPerSecond = clock.driftUncertaintyNanosPerSecond
                .takeIf { clock.hasDriftUncertaintyNanosPerSecond() },
            elapsedRealtimeNanos = clock.elapsedRealtimeNanos.takeIf { clock.hasElapsedRealtimeNanos() },
            elapsedRealtimeUncertaintyNanos = clock.elapsedRealtimeUncertaintyNanos
                .takeIf { clock.hasElapsedRealtimeUncertaintyNanos() },
        )
        val receiverTime = GpsReceiverClock.decode(batch).epoch?.time ?: return emptyList()
        val ephemerides = RawGnssSynthesis.ephemerides(null, System.currentTimeMillis())
        return observations.mapNotNull { measurement ->
            val ephemeris = ephemerides.firstOrNull { it.svid == measurement.svid } ?: return@mapNotNull null
            var transmitTime = GpsTime(receiverTime.week, measurement.receivedSvTimeNanos / 1e9)
            if (receiverTime.secondsSince(transmitTime) < 0.0) {
                transmitTime = transmitTime.plusSeconds(-GpsTime.SECONDS_PER_WEEK)
            }
            val geometry = GpsReceptionGeometry.observe(
                ephemeris, receiverTime, target, altitudeMeters,
            ).observation ?: return@mapNotNull null
            val light = GpsReceptionGeometry.SPEED_OF_LIGHT_METERS_PER_SECOND
            val meters = (receiverTime.secondsSince(transmitTime) + geometry.l1CaClockBiasSeconds) * light
            if (!meters.isFinite() || meters <= 0.0) null
            else GpsRangeObservation(geometry.satelliteInReceptionEcef, meters)
        }
    }
}
