package com.system.location.service

import android.Manifest
import android.content.pm.PackageManager
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.runtime.RuntimeTransferWire
import com.system.location.service.hook.RuntimeSyncCommand
import com.system.location.service.hook.security.CommandClient
import com.system.location.service.hook.security.SampleWire
import com.system.location.service.runtime.ScenarioRuntime
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real receiver registration/callback checks with an authenticated, explicitly absent profile. */
@RunWith(AndroidJUnit4::class)
class GnssPassthroughDeviceTest {
    @Test fun absentProfilePreservesPhysicalCallbacksAndRegistrationLifecycle() = runBlocking {
        assumeTrue("Explicit device run required", InstrumentationRegistry.getArguments().getString("libxposedDevice") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(LocationManager::class.java)
        ScenarioRuntime.awaitInitialized()
        assumeFalse("Preserve local simulation", ScenarioRuntime.state.value.isActive)
        for (permission in listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            assumeTrue("Existing permission required: $permission",
                context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED)
        }
        assumeTrue("GPS must already be enabled", manager.isProviderEnabled(LocationManager.GPS_PROVIDER))
        val client = CommandClient()
        assertTrue("Authenticated connection required", client.connect(manager))
        fun command(name: String, fill: Bundle.() -> Unit = {}): Bundle = Bundle().apply {
            putString("command_id", name); fill()
            assertTrue("Command failed: $name", client.send(manager, this))
        }
        val initial = command("get_runtime_status")
        assertEquals("SYSTEM_SERVER", initial.getString("hook_scope"))
        assumeFalse("Preserve system simulation", initial.getBoolean("is_start"))
        val snapshot = RuntimeTransferWire.decode(checkNotNull(command("sync_config").getByteArray(RuntimeSyncCommand.PAYLOAD)))
        assumeTrue("Requires an absent profile; do not clear user evidence", snapshot.state.configuration.gnssSource == null)

        fun registrations(header: String): Int {
            val dump = ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("dumpsys location"))
                .bufferedReader().use { it.readText() }.lines()
            val start = dump.indices.single { dump[it].trim() == header }
            val indent = dump[start].takeWhile(Char::isWhitespace).length
            val section = dump.drop(start + 1).takeWhile { it.isBlank() || it.takeWhile(Char::isWhitespace).length > indent }
            check(section.any { it.trimStart().startsWith("service:") }) { "Provider dump has no service state" }
            val packageEntry = Regex("^\\s*\\d+/${Regex.escape(context.packageName)}(?:\\[|/|\\s|$)")
            return section.count { packageEntry.containsMatchIn(it) }
        }
        assumeTrue("Preserve preexisting registrations", registrations("Status Provider:") == 0 && registrations("Measurements Provider:") == 0)
        val statusEvents = LinkedBlockingQueue<Int>()
        val status = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) { statusEvents.offer(status.satelliteCount) }
        }
        val existing = object : GnssMeasurementsEvent.Callback() {}
        val addedWhileActive = object : GnssMeasurementsEvent.Callback() {}
        val location = LocationListener { }
        var renewal: Job? = null
        try {
            assertTrue(manager.registerGnssStatusCallback({ it.run() }, status))
            assertTrue(manager.registerGnssStatusCallback({ it.run() }, status))
            assertEquals("Duplicate status registration", 1, registrations("Status Provider:"))
            assertTrue(manager.registerGnssMeasurementsCallback({ it.run() }, existing))
            assertEquals(1, registrations("Measurements Provider:"))
            fun publish() {
                command("publish_sample") {
                    SampleWire.write(this, LocationSample(Wgs84(31.230416, 121.473701),
                        altitude = 42.0, accuracy = 5f, speed = 0f, bearing = 73f,
                        timeMillis = System.currentTimeMillis(), elapsedNanos = SystemClock.elapsedRealtimeNanos()))
                }
            }
            publish()
            renewal = launch(Dispatchers.IO) { while (isActive) { delay(500L); publish() } }
            assertTrue("Without a frame, raw registration must reach Android",
                manager.registerGnssMeasurementsCallback({ it.run() }, addedWhileActive))
            assertEquals(2, registrations("Measurements Provider:"))
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, { it.run() }, location)
            // No captured source exists, so these callbacks can only be the real provider stream.
            repeat(2) { assertNotNull("Physical status was suppressed while no profile exists", statusEvents.poll(8, TimeUnit.SECONDS)) }
            assertTrue(command("get_runtime_status").getBoolean("is_start"))
            manager.unregisterGnssMeasurementsCallback(existing)
            assertEquals(1, registrations("Measurements Provider:"))
            manager.unregisterGnssStatusCallback(status)
            assertEquals(0, registrations("Status Provider:"))
            renewal.cancelAndJoin()
            command("stop")
            manager.unregisterGnssMeasurementsCallback(addedWhileActive)
            assertEquals(0, registrations("Measurements Provider:"))
        } finally {
            renewal?.cancelAndJoin()
            try {
                manager.removeUpdates(location)
                manager.unregisterGnssStatusCallback(status)
                manager.unregisterGnssMeasurementsCallback(existing)
                manager.unregisterGnssMeasurementsCallback(addedWhileActive)
            } finally {
                assertFalse(command("stop").let { command("get_runtime_status").getBoolean("is_start") })
            }
        }
    }
}
