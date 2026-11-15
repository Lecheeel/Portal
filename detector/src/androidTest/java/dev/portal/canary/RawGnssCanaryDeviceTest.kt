package dev.portal.canary

import android.Manifest
import android.content.pm.PackageManager
import android.location.GnssMeasurement
import android.location.GnssMeasurementsEvent
import android.location.LocationManager
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Independent UID proves the system-server hook delivers synthetic raw, not a rejection/real leak. */
class RawGnssCanaryDeviceTest : CanaryDeviceFixture() {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager get() = context.getSystemService(LocationManager::class.java)
    private fun report(json: JSONObject) = InstrumentationRegistry.getInstrumentation()
        .sendStatus(0, Bundle().apply { putString("canary", json.toString()) })

    @Test fun rawMeasurementsArriveFromSyntheticProducer() {
        assumeTrue("Fine location needed", context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED)
        val statuses = LinkedBlockingQueue<Int>()
        val events = LinkedBlockingQueue<GnssMeasurementsEvent>()
        val first = CountDownLatch(1)
        val callback = object : GnssMeasurementsEvent.Callback() {
            override fun onStatusChanged(status: Int) { statuses.offer(status) }
            override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
                events.offer(event)
                first.countDown()
            }
        }
        assumeTrue("Raw registration unavailable", manager.registerGnssMeasurementsCallback({ it.run() }, callback))
        try {
            assertTrue("No synthetic raw event", first.await(12, TimeUnit.SECONDS))
            val event = requireNotNull(events.poll())
            val measurements = event.measurements.toList()
            var ready = false
            repeat(4) {
                val status = statuses.poll(2, TimeUnit.SECONDS) ?: return@repeat
                if (status == GnssMeasurementsEvent.Callback.STATUS_READY) ready = true
            }
            assertTrue("Synthetic reader never reported READY", ready)
            assertTrue("Need GPS measurements", measurements.size >= 4)
            assertTrue(measurements.all { it.constellationType == 1 && it.svid in 1..32 })
            assertTrue(measurements.all { it.hasCarrierFrequencyHz() })
            assertTrue(measurements.all {
                it.accumulatedDeltaRangeState and GnssMeasurement.ADR_STATE_VALID != 0
            })
            assertTrue(event.clock.hasFullBiasNanos() && event.clock.hasElapsedRealtimeNanos())
            report(JSONObject().put("check", "raw")
                .put("uid", android.os.Process.myUid())
                .put("measurements", measurements.size)
                .put("svids", org.json.JSONArray(measurements.map { it.svid }))
                .put("status", GnssMeasurementsEvent.Callback.STATUS_READY))
        } finally {
            manager.unregisterGnssMeasurementsCallback(callback)
        }
    }
}
