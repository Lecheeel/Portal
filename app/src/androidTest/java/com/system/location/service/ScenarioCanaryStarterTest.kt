package com.system.location.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.core.backend.BackendType
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.scenario.MovementProfile
import com.system.location.service.core.scenario.Scenario
import com.system.location.service.runtime.ScenarioRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-side canary starter. It leaves the Xposed scenario running so the detector package can
 * exercise the public location/Wi-Fi/cell APIs from a different, permission-denied UID.
 */
@RunWith(AndroidJUnit4::class)
class ScenarioCanaryStarterTest {
    @Test
    fun startXposedScenarioForPermissionCanary() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(ScenarioRuntime.selectBackend(BackendType.XPOSED).await())
        val started = ScenarioRuntime.start(
            Scenario(
                id = "canary",
                name = "Canary",
                point = Wgs84(31.2304, 121.4737),
                profile = MovementProfile(
                    speedMps = 0.0,
                    altitudeMeters = 12.0,
                    accuracyMeters = 2.0f,
                    intervalMs = 500L,
                ),
            ),
        ).await()
        assertTrue("scenario did not start on $context", started)
        // Keep the instrumentation (and therefore the app process and foreground service) alive
        // while the detector package runs its permission canaries from a different UID.
        Thread.sleep(600_000L)
    }
}
