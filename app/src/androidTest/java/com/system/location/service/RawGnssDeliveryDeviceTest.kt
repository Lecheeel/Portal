package com.system.location.service

import android.location.GnssMeasurement
import android.location.GnssMeasurementsEvent
import android.location.LocationManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.core.backend.BackendType
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.scenario.MovementProfile
import com.system.location.service.core.scenario.Scenario
import com.system.location.service.runtime.ScenarioRuntime
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
        } finally {
            manager.unregisterGnssMeasurementsCallback(callback)
            ScenarioRuntime.stop().await()
        }
    }
}
