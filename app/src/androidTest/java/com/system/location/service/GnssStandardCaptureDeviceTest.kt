package com.system.location.service

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.core.gnss.GnssCaptureCodec
import com.system.location.service.core.gnss.GnssCaptureValidator
import com.system.location.service.gnss.GnssReceiverCollector
import com.system.location.service.runtime.ScenarioRuntime
import com.system.location.service.service.MockServiceHelper
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real evidence on the standard backend; never installs a fixture or changes a saved profile. */
@RunWith(AndroidJUnit4::class)
class GnssStandardCaptureDeviceTest {
    @Test fun collectPhysicalWindowWithoutHookAuthority() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("standardProviderDevice") == "true")
        val context = instrumentation.targetContext
        val manager = context.getSystemService(LocationManager::class.java)
        ScenarioRuntime.awaitInitialized()
        assumeFalse("Preserve active simulation", ScenarioRuntime.state.value.isActive)
        for (permission in listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            assumeTrue("Existing permission required: $permission",
                context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED)
        }
        assumeTrue(manager.isProviderEnabled(LocationManager.GPS_PROVIDER))
        MockServiceHelper.tryInitService(manager)
        assertNull("Standard comparison requires absent Hook authority", MockServiceHelper.runtimeStatus(manager))
        val duration = arguments.getString("physicalCaptureMillis")?.toLong() ?: 120_000L
        require(duration in 30_000L..300_000L)
        val capture = withTimeout(duration + 15_000L) { GnssReceiverCollector(context).capture(duration) }
        File(context.cacheDir, "gnss-standard-physical-window.json").writeText(GnssCaptureCodec.encode(capture))
        val quality = GnssCaptureValidator.validate(capture, System.currentTimeMillis())
        instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("physical_capture_quality", "eligible=${quality.eligible}; ${quality.issues.joinToString()}")
            putInt("physical_capture_frames", capture.frames.size)
            putInt("physical_capture_usable_samples", capture.frames.sumOf { frame ->
                frame.satellites.count(GnssCaptureValidator::isUsableSatellite)
            })
            putInt("measurement_batches", capture.measurements.size)
            putInt("navigation_messages", capture.navigation.size)
        })
        // Quality is reported separately: collecting rejected evidence is not profile acceptance.
        assertTrue(capture.frames.isNotEmpty())
        assertNull(MockServiceHelper.runtimeStatus(manager))
    }
}
