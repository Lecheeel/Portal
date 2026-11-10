package com.system.location.service

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.location.LocationSample
import com.system.location.service.gnss.GnssReceiverCollector
import com.system.location.service.runtime.ScenarioRuntime
import com.system.location.service.service.MockServiceHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in system-server checks. Never save fixture data or change the user's backend/settings. */
@RunWith(AndroidJUnit4::class)
class GnssCaptureIsolationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(LocationManager::class.java)

    @Before fun requireStoppedDevice() = runBlocking {
        assumeTrue("Enable explicitly with -e libxposedDevice true",
            InstrumentationRegistry.getArguments().getString("libxposedDevice") == "true")
        ScenarioRuntime.awaitInitialized()
        assumeFalse("Preserve active local simulation", ScenarioRuntime.state.value.isActive)
        for (permission in listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            assumeTrue("Existing permission required: $permission",
                context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED)
        }
        assumeTrue("GPS must already be enabled", manager.isProviderEnabled(LocationManager.GPS_PROVIDER))
        if (!MockServiceHelper.isServiceInit()) MockServiceHelper.tryInitService(manager)
        val status = checkNotNull(MockServiceHelper.runtimeStatus(manager))
        assertEquals("SYSTEM_SERVER", status.getString("hook_scope"))
        assertTrue("Reload current module first", status.getString("runtime_instance")?.isNotBlank() == true)
        assumeFalse("Preserve active system simulation", status.getBoolean("is_start"))
    }

    @Test fun remoteActiveWithIdleUiCannotBeCaptured() = runBlocking {
        try {
            assertTrue(MockServiceHelper.publishSample(manager, sample()))
            assertFalse("UI must remain idle for this regression", ScenarioRuntime.state.value.isActive)
            val failure = runCatching {
                withTimeout(5_000L) { GnssReceiverCollector(context).capture(30_000L) }
            }.exceptionOrNull()
            assertTrue("Expected authoritative pre-registration rejection: $failure",
                failure is IllegalStateException && failure.message.orEmpty().contains("停止系统模拟"))
        } finally { stopSystem() }
    }

    @Test fun completedRemoteSessionDuringCaptureInvalidatesWindowAndReleasesCollector() = runBlocking {
        val firstFrame = CompletableDeferred<Unit>()
        val capture = async(Dispatchers.Default) {
            runCatching { GnssReceiverCollector(context).capture(30_000L) { firstFrame.complete(Unit) } }
        }
        try {
            withTimeout(15_000L) { firstFrame.await() }
            val before = checkNotNull(MockServiceHelper.runtimeStatus(manager))
            withContext(Dispatchers.IO) {
                assertTrue(MockServiceHelper.publishSample(manager, sample()))
                stopSystem()
            }
            val after = checkNotNull(MockServiceHelper.runtimeStatus(manager))
            assertEquals(before.getString("runtime_instance"), after.getString("runtime_instance"))
            assertEquals(before.getLong("simulation_generation") + 2, after.getLong("simulation_generation"))
            assertFalse(after.getBoolean("is_start"))
            val failure = withTimeout(5_000L) { capture.await() }.exceptionOrNull()
            assertTrue("A fully stopped session still contaminates the window: $failure",
                failure is IllegalStateException && failure.message.orEmpty().contains("系统模拟状态发生变化"))
        } finally {
            capture.cancelAndJoin()
            stopSystem()
        }
        // A new callback proves the failure path unregistered and released the singleton gate.
        val retryFrame = CompletableDeferred<Unit>()
        val retry = async(Dispatchers.Default) {
            GnssReceiverCollector(context).capture(30_000L) { retryFrame.complete(Unit) }
        }
        try { withTimeout(15_000L) { retryFrame.await() } }
        finally { retry.cancelAndJoin() }
    }

    private fun stopSystem() {
        assertTrue("Stop command failed", MockServiceHelper.tryCloseMock(manager))
        assertFalse(checkNotNull(MockServiceHelper.runtimeStatus(manager)).getBoolean("is_start"))
    }

    private fun sample() = LocationSample(
        coordinate = Wgs84(31.230416, 121.473701), altitude = 42.0,
        accuracy = 5f, speed = 0f, bearing = 73f,
        timeMillis = System.currentTimeMillis(), elapsedNanos = SystemClock.elapsedRealtimeNanos(),
    )
}
