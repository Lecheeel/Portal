package com.system.location.service

import android.Manifest
import android.content.pm.PackageManager
import android.location.GnssMeasurementsEvent
import android.location.LocationManager
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.location.LocationSample
import com.system.location.service.service.MockServiceHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the raw-measurement add/remove points owned by LocationNMEAHook. This is not an NMEA
 * callback test. Only current registrations are inspected; dumps and coordinates are never logged.
 */
@RunWith(AndroidJUnit4::class)
class LibXposedNmeaDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(LocationManager::class.java)

    @Before
    fun requireExplicitDeviceRun() {
        assumeTrue(
            "Enable explicitly with -e libxposedDevice true",
            InstrumentationRegistry.getArguments().getString("libxposedDevice") == "true",
        )
    }

    @Test
    fun existingRawMeasurementRegistrationCanBeRemovedWhileNewOnesAreSuppressed() {
        if (!MockServiceHelper.isServiceInit()) MockServiceHelper.tryInitService(manager)
        assertTrue("Authenticated controller handshake failed", MockServiceHelper.isServiceInit())
        assumeFalse("Preserving the user's active simulation", status().getBoolean("is_start"))
        for (permission in listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )) {
            assumeTrue(
                "Existing permission required: $permission",
                context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED,
            )
        }
        assumeTrue("GPS must already exist", manager.hasProvider(LocationManager.GPS_PROVIDER))
        assumeTrue("GPS must already be enabled", manager.isProviderEnabled(LocationManager.GPS_PROVIDER))
        assumeTrue(
            "Preserving an existing raw-measurement registration from this package",
            currentRegistrationCount() == 0,
        )

        val existing = object : GnssMeasurementsEvent.Callback() {}
        val suppressed = object : GnssMeasurementsEvent.Callback() {}
        var publicationAttempted = false
        var lastPublication = 0L
        val deadline = SystemClock.elapsedRealtime() + 9_000L

        fun renewLeaseIfNeeded(force: Boolean = false) {
            if (!force && SystemClock.elapsedRealtime() - lastPublication < 1_000L) return
            publicationAttempted = true
            val sample = LocationSample(
                coordinate = Wgs84(31.230416, 121.473701),
                altitude = 42.0,
                accuracy = 5f,
                speed = 0f,
                bearing = 73f,
                timeMillis = System.currentTimeMillis(),
                elapsedNanos = SystemClock.elapsedRealtimeNanos(),
            )
            assertTrue("Sample command failed", MockServiceHelper.publishSample(manager, sample))
            lastPublication = SystemClock.elapsedRealtime()
            val active = status()
            assertTrue("Published sample did not activate the backend", active.getBoolean("is_start"))
            assertEquals("Backend did not accept this sample", sample.timeMillis, active.getLong("last_publish"))
        }

        fun awaitCount(expected: Int, active: Boolean): Boolean {
            val phaseDeadline = minOf(deadline, SystemClock.elapsedRealtime() + 2_000L)
            do {
                if (active) renewLeaseIfNeeded()
                val count = currentRegistrationCount()
                if (active) assertTrue("Backend expired during registration observation", status().getBoolean("is_start"))
                if (count == expected) return true
                SystemClock.sleep(100)
            } while (SystemClock.elapsedRealtime() < phaseDeadline)
            return false
        }

        try {
            assertTrue(
                "Android rejected the initial raw-measurement registration",
                manager.registerGnssMeasurementsCallback({ task -> task.run() }, existing),
            )
            assertTrue("No real raw-measurement registration appeared", awaitCount(1, active = false))
            renewLeaseIfNeeded(force = true)

            // Observe the add gate instead of assuming that the user's enableNMEA value is false.
            manager.registerGnssMeasurementsCallback({ task -> task.run() }, suppressed)
            val gateDeadline = minOf(deadline, SystemClock.elapsedRealtime() + 500L)
            var gateSuppressed = true
            do {
                renewLeaseIfNeeded()
                gateSuppressed = gateSuppressed && currentRegistrationCount() == 1
                assertTrue("Backend expired during add-gate observation", status().getBoolean("is_start"))
                SystemClock.sleep(100)
            } while (SystemClock.elapsedRealtime() < gateDeadline)
            assumeTrue(
                "Raw-listener add suppression is not observable; enableNMEA may be enabled or the add path differs. Settings were preserved",
                gateSuppressed,
            )

            renewLeaseIfNeeded(force = true)
            manager.unregisterGnssMeasurementsCallback(existing)
            assertTrue("Existing raw registration remained while simulation was active", awaitCount(0, active = true))
        } finally {
            try {
                try {
                    manager.unregisterGnssMeasurementsCallback(existing)
                } finally {
                    manager.unregisterGnssMeasurementsCallback(suppressed)
                }
            } finally {
                if (publicationAttempted) {
                    assertTrue("Stop command failed", MockServiceHelper.tryCloseMock(manager))
                    assertFalse("Simulation remained active after cleanup", status().getBoolean("is_start"))
                }
            }
        }
    }

    private fun status(): Bundle {
        val result = checkNotNull(MockServiceHelper.runtimeStatus(manager)) { "No authenticated runtime response" }
        assertEquals("Expected the system-server backend", "SYSTEM_SERVER", result.getString("hook_scope"))
        assertTrue("Missing authoritative start state", result.containsKey("is_start"))
        return result
    }

    private fun currentRegistrationCount(): Int {
        // Shell access is used solely for read-only dumpsys, never root, permissions or settings.
        val dump = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("dumpsys location"),
        ).bufferedReader().use { it.readText() }
        val lines = dump.lineSequence().toList()
        val headers = lines.indices.filter { lines[it].trim() == "Measurements Provider:" }
        check(headers.size == 1) { "Cannot identify a unique raw-measurements provider section" }
        val start = headers.single()
        val indent = lines[start].takeWhile { it.isWhitespace() }.length
        val section = lines.drop(start + 1).takeWhile {
            it.isBlank() || it.takeWhile(Char::isWhitespace).length > indent
        }
        check(section.any { it.trimStart().startsWith("service:") }) {
            "Raw-measurements provider dump does not expose current service state"
        }
        val packageEntry = Regex("^\\s*\\d+/${Regex.escape(context.packageName)}(?:\\[|/|\\s|$)")
        // Current listener entries begin with uid/package. The boundary above excludes Event Log.
        return section.count { packageEntry.containsMatchIn(it) }
    }
}
