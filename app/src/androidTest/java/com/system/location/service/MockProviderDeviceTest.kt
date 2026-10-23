package com.system.location.service

import android.Manifest
import android.app.AppOpsManager
import android.app.ActivityManager
import android.content.Intent
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.backend.mock.AndroidMockProviderPort
import com.system.location.service.backend.mock.MockAuthorizationException
import com.system.location.service.backend.mock.MockFlagExperiment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import com.system.location.service.runtime.ScenarioRuntime
import com.system.location.service.ui.diagnostics.LocationProbeActivity
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class MockProviderDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(LocationManager::class.java)
    private val provider = "portal_device_test_${Process.myPid()}"

    private fun setMockMode(mode: String) {
        instrumentation.uiAutomation.executeShellCommand("appops set ${context.packageName} android:mock_location $mode")
            .let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() } }
    }
    private fun setLocationEnabled(enabled: Boolean) {
        instrumentation.uiAutomation.executeShellCommand("cmd location set-location-enabled $enabled")
            .let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() } }
    }
    private fun withAuthorization(test: (AndroidMockProviderPort) -> Unit) {
        listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
        }
        val previous = context.getSystemService(AppOpsManager::class.java)
            .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName)
        val restore = when (previous) {
            AppOpsManager.MODE_ALLOWED -> "allow"
            AppOpsManager.MODE_IGNORED -> "ignore"
            AppOpsManager.MODE_ERRORED -> "deny"
            AppOpsManager.MODE_FOREGROUND -> "foreground"
            else -> "default"
        }
        val port = AndroidMockProviderPort(context)
        val locationEnabled = manager.isLocationEnabled
        try {
            setMockMode("allow")
            assertTrue("Mock AppOp must be granted for the test", port.permissionGranted())
            if (!locationEnabled) setLocationEnabled(true)
            test(port)
        }
        finally {
            try { setMockMode("allow"); port.remove(provider) }
            finally {
                try { setMockMode(restore) }
                finally { if (!locationEnabled) setLocationEnabled(false) }
            }
        }
    }

    @Test fun submittedLocationIsMarkedMockAfterSystemRoundTrip() = withAuthorization { port ->
        assertTrue("Location must be enabled for delivery", manager.isLocationEnabled)
        port.register(provider); port.enable(provider, true)
        val received = AtomicReference<Location>()
        val delivered = CountDownLatch(1)
        val listener = LocationListener { received.set(Location(it)); delivered.countDown() }
        try {
            manager.requestLocationUpdates(provider, 0L, 0f, context.mainExecutor, listener)
            val outgoing = Location(provider).apply {
                latitude = 25.123456; longitude = 119.123456; accuracy = 3f
                time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            MockFlagExperiment().clear(outgoing)
            assertFalse(outgoing.isMock)
            manager.setTestProviderLocation(provider, outgoing)
            assertTrue("No provider callback within 10 seconds", delivered.await(10, TimeUnit.SECONDS))
            val incoming = received.get()
            assertTrue("System delivery must retain the standard mock marker", incoming.isMock)
            assertEquals(outgoing.latitude, incoming.latitude, 0.0)
            assertEquals(outgoing.elapsedRealtimeNanos, incoming.elapsedRealtimeNanos)
            assertTrue(manager.getLastKnownLocation(provider)!!.isMock)
        } finally { manager.removeUpdates(listener) }
    }

    @Test fun ignoredAuthorizationCannotDiscardPendingProviderCleanup() = withAuthorization { port ->
        port.register(provider); port.enable(provider, true)
        setMockMode("ignore")
        assertFalse(port.permissionGranted())
        assertThrows(MockAuthorizationException::class.java) { port.remove(provider) }
        assertTrue(port.pendingProviders().contains(provider))
        setMockMode("allow")
        port.remove(provider)
        assertFalse(port.pendingProviders().contains(provider))
    }

    @Test fun observerProcessCannotRecoverOrRemoveMainProcessProviders() = withAuthorization { port ->
        runBlocking { ScenarioRuntime.refreshDiagnostics().await() }
        val preferences = context.getSharedPreferences("scenario_runtime", android.content.Context.MODE_PRIVATE)
        val interrupted = preferences.getBoolean("interrupted", false)
        val backend = preferences.getString("backend", null)
        port.register(provider); port.enable(provider, true)
        preferences.edit().putBoolean("interrupted", true).putString("backend", "MOCK_PROVIDER").commit()
        try {
            context.startActivity(Intent(context, LocationProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val activities = context.getSystemService(ActivityManager::class.java)
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (activities.getRunningTasks(5).none { it.topActivity?.className == LocationProbeActivity::class.java.name } && SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(50)
            }
            assertTrue(activities.getRunningTasks(5).any { it.topActivity?.className == LocationProbeActivity::class.java.name })
            val process = activities.runningAppProcesses.single { it.processName == "${context.packageName}:location_probe" }
            assertNotEquals(Process.myPid(), process.pid)
            assertTrue(port.pendingProviders().contains(provider))
            assertTrue(manager.allProviders.contains(provider))
            assertTrue(preferences.getBoolean("interrupted", false))
            instrumentation.uiAutomation.waitForIdle(200, 5000)
            val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            val file = java.io.File(context.getExternalFilesDir(null), "location-probe.png")
            try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
            instrumentation.uiAutomation.executeShellCommand("mkdir -p /sdcard/Download/Portal-ui-captures")
                .let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() } }
            instrumentation.uiAutomation.executeShellCommand("cp ${file.absolutePath} /sdcard/Download/Portal-ui-captures/location-probe.png")
                .let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() } }
        } finally {
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            preferences.edit().putBoolean("interrupted", interrupted).apply {
                if (backend == null) remove("backend") else putString("backend", backend)
            }.commit()
        }
    }
}
