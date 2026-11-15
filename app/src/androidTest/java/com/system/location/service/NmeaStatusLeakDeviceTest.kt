package com.system.location.service

import android.location.GnssStatus
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.core.backend.BackendType
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.scenario.MovementProfile
import com.system.location.service.core.scenario.Scenario
import com.system.location.service.ext.enableNMEA
import com.system.location.service.runtime.ScenarioRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A protected listener must not receive real NMEA or real status lifecycle/TTFF data while the
 * simulation is active, even if synthetic NMEA is disabled or no synthetic frame is available.
 */
@RunWith(AndroidJUnit4::class)
class NmeaStatusLeakDeviceTest {
    @Test fun protectedListenerNeverSeesRealNmeaOrStatusLifecycle() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(LocationManager::class.java)
        assumeTrue("Xposed backend unavailable", ScenarioRuntime.selectBackend(BackendType.XPOSED).await())
        val oldNmea = context.enableNMEA
        context.enableNMEA = false
        val scenario = Scenario(
            id = "leak-canary",
            name = "Leak canary",
            point = Wgs84(31.2304, 121.4737),
            profile = MovementProfile(speedMps = 0.0, altitudeMeters = 12.0, accuracyMeters = 2.0f, intervalMs = 500L),
        )
        assumeTrue("scenario did not start", ScenarioRuntime.start(scenario).await())
        val nmea = CopyOnWriteArrayList<String>()
        var started = 0
        var firstFix = 0
        var stopped = 0
        val nmeaListener = OnNmeaMessageListener { message, _ -> nmea += message }
        val status = object : GnssStatus.Callback() {
            override fun onStarted() { started++ }
            override fun onFirstFix(ttffMillis: Int) { firstFix++ }
            override fun onStopped() { stopped++ }
        }
        try {
            assertTrue(manager.addNmeaListener({ it.run() }, nmeaListener))
            assertTrue(manager.registerGnssStatusCallback({ it.run() }, status))
            Thread.sleep(6_000)
            assertEquals("Protected listener received real NMEA", 0, nmea.size)
            // One synthetic lifecycle is expected. A second call would mean a real framework
            // onStarted/onFirstFix leaked through the guard.
            assertEquals("Unexpected onStarted count", 1, started)
            assertEquals("Unexpected onFirstFix count", 1, firstFix)
            assertEquals("Protected listener received synthetic or real onStopped", 0, stopped)
        } finally {
            manager.removeNmeaListener(nmeaListener)
            manager.unregisterGnssStatusCallback(status)
            context.enableNMEA = oldNmea
            ScenarioRuntime.stop().await()
        }
    }
}
