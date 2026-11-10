package com.system.location.service

import android.Manifest
import android.app.AppOpsManager
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.backend.mock.AndroidMockProviderPort
import com.system.location.service.backend.mock.MockProviderBackend
import com.system.location.service.backend.mock.MockProviderPort
import com.system.location.service.core.backend.BackendResult
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.location.LocationSample
import com.system.location.service.runtime.ScenarioRuntime
import com.system.location.service.service.MockServiceHelper
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run only after disabling the module and rebooting; this is a standard API comparison. */
@RunWith(AndroidJUnit4::class)
class MockProviderBaselineDeviceTest {
    @Test fun standardBackendRoundTripsAndCleansUpWithoutSynthesizingGnssFields() = runBlocking {
        assumeTrue("Explicit isolated run required",
            InstrumentationRegistry.getArguments().getString("standardProviderDevice") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(LocationManager::class.java)
        ScenarioRuntime.awaitInitialized()
        assumeFalse("Preserve active simulation", ScenarioRuntime.state.value.isActive)
        for (permission in listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            assumeTrue("Existing permission required: $permission",
                context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED)
        }
        assumeTrue("GPS must already be enabled", manager.isProviderEnabled(LocationManager.GPS_PROVIDER))
        MockServiceHelper.tryInitService(manager)
        assertNull("The Hook authority must be absent", MockServiceHelper.runtimeStatus(manager))
        val realPort = AndroidMockProviderPort(context)
        assumeTrue("Preserve existing provider ownership", realPort.pendingProviders().isEmpty())
        // Exercise the mandatory GPS/network channels without changing the optional fused setting.
        val port = object : MockProviderPort by realPort { override fun optionalProviders() = emptySet<String>() }
        val backend = MockProviderBackend(port)
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val originalMode = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName)
        val restoreMode = when (originalMode) {
            AppOpsManager.MODE_ALLOWED -> "allow"
            AppOpsManager.MODE_IGNORED -> "ignore"
            AppOpsManager.MODE_ERRORED -> "deny"
            AppOpsManager.MODE_FOREGROUND -> "foreground"
            else -> "default"
        }
        fun authorization(mode: String) {
            instrumentation.uiAutomation.executeShellCommand("appops set ${context.packageName} android:mock_location $mode")
                .let { ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() } }
        }
        val callbacks = LinkedBlockingQueue<Location>()
        val listener = LocationListener { callbacks.offer(Location(it)) }
        var registered = false
        try {
            authorization("allow")
            repeat(2) {
                assertEquals(BackendResult.Success, backend.prepare())
                assertEquals(BackendResult.Success, backend.start())
                if (!registered) {
                    manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, { it.run() }, listener)
                    registered = true
                }
                for (index in 0..2) {
                    val sample = LocationSample(Wgs84(31.230416 + index * 0.001, 121.473701),
                        altitude = 42.0, accuracy = 5f, speed = 2f, bearing = 73f,
                        timeMillis = System.currentTimeMillis(), elapsedNanos = SystemClock.elapsedRealtimeNanos())
                    assertEquals(BackendResult.Success, backend.publish(sample, null))
                    val deadline = SystemClock.elapsedRealtime() + 5_000L
                    var incoming: Location? = null
                    while (incoming == null && SystemClock.elapsedRealtime() < deadline) {
                        val candidate = callbacks.poll(100L, TimeUnit.MILLISECONDS)
                        if (candidate?.elapsedRealtimeNanos == sample.elapsedNanos) incoming = candidate
                    }
                    assertNotNull("No current GPS callback", incoming)
                    val fix = checkNotNull(incoming)
                    assertTrue("Android must retain the standard mock marker", fix.isMock)
                    assertEquals(sample.latitude, fix.latitude, 0.0)
                    assertEquals(sample.longitude, fix.longitude, 0.0)
                    for (key in listOf("satellites", "meanCn0", "maxCn0")) {
                        assertFalse("Standard backend invented $key", fix.extras?.containsKey(key) == true)
                    }
                    assertTrue(checkNotNull(manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)).isMock)
                }
                assertEquals(BackendResult.Success, backend.stop())
                assertTrue("Stop must clear durable ownership", realPort.pendingProviders().isEmpty())
            }
        } finally {
            try {
                if (registered) manager.removeUpdates(listener)
            } finally {
                try {
                    authorization("allow")
                    assertEquals(BackendResult.Success, backend.stop())
                } finally { authorization(restoreMode) }
            }
        }
    }
}
