package com.system.location.service

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Bundle
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.location.LocationSample
import com.system.location.service.hook.security.SampleWire
import com.system.location.service.service.MockServiceHelper
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicitly enabled API102 device checks through the controller's own UID. These results do not
 * establish third-party app visibility, detection behavior, or other devices/frameworks.
 */
@RunWith(AndroidJUnit4::class)
class LibXposedDeviceTest {
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
    fun authenticatedRuntimeStatusComesFromSystemServer() {
        val status = connectAndReadStatus()
        assertEquals("SYSTEM_SERVER", status.getString("hook_scope"))
        assertTrue("No installed system hooks reported", status.getInt("hook_installed") > 0)
        assertTrue("The command hook must have executed", status.getInt("hook_matched") > 0)
        assertTrue("Missing authoritative start state", status.containsKey("is_start"))
        assertTrue(
            "Unsupported sample protocol",
            SampleWire.isCompatible(status.getInt("sample_version")),
        )
    }

    @Test
    fun publishedSamplesReturnThroughGpsCacheAndContinuousListenerThenStop() {
        val initial = connectAndReadStatus()
        assumeFalse("Preserving the user's active simulation", initial.getBoolean("is_start"))
        // No UI is launched and no permission/AppOp/provider setting is changed by this fixture.
        for (permission in
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            )) {
            assumeTrue("Existing permission required: $permission", hasPermission(permission))
        }
        assumeTrue("GPS must already exist", manager.hasProvider(LocationManager.GPS_PROVIDER))
        assumeTrue(
            "GPS must already be enabled",
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER),
        )

        val received = LinkedBlockingQueue<Location>()
        val listener = LocationListener { received.offer(Location(it)) }
        var registrationAttempted = false
        var publicationAttempted = false
        try {
            // Publish first so registration exercises the active system-server hook.
            val first = sample(31.230416, 121.473701)
            publicationAttempted = true
            assertTrue(
                "First sample command failed",
                MockServiceHelper.publishSample(manager, first),
            )
            assertActive(first)
            assertSample(
                first,
                manager.getLastKnownLocation(LocationManager.GPS_PROVIDER),
                "GPS cache",
            )

            registrationAttempted = true
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                0L,
                0f,
                { task -> task.run() },
                listener,
            )
            // Two separate publications exercise the same listener registration continuously.
            for (index in 1..2) {
                val outgoing = sample(31.230416 + index * 0.01, 121.473701 + index * 0.01)
                assertTrue(
                    "Sample $index command failed",
                    MockServiceHelper.publishSample(manager, outgoing),
                )
                assertActive(outgoing)
                assertSample(
                    outgoing,
                    manager.getLastKnownLocation(LocationManager.GPS_PROVIDER),
                    "GPS cache $index",
                )
                assertSample(
                    outgoing,
                    awaitSample(received, outgoing.elapsedNanos),
                    "GPS callback $index",
                )
            }
        } finally {
            try {
                if (registrationAttempted) manager.removeUpdates(listener)
            } finally {
                if (publicationAttempted) {
                    assertTrue("Stop command failed", MockServiceHelper.tryCloseMock(manager))
                    val stopped = requireStatus()
                    assertTrue("Stop response has no start state", stopped.containsKey("is_start"))
                    assertFalse(
                        "Simulation remained active after cleanup",
                        stopped.getBoolean("is_start"),
                    )
                }
            }
        }
    }

    @Test
    fun currentLocationCallbackUsesPublishedSampleWhenGpsCanSupplyAFix() {
        val initial = connectAndReadStatus()
        assumeFalse("Preserving the user's active simulation", initial.getBoolean("is_start"))
        assumeTrue("Fine location permission required", hasPermission(Manifest.permission.ACCESS_FINE_LOCATION))
        assumeTrue("Background location permission required", hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        assumeTrue("GPS must already be enabled", manager.isProviderEnabled(LocationManager.GPS_PROVIDER))

        val baselineMillis = InstrumentationRegistry.getArguments()
            .getString("gpsBaselineMillis")?.toLongOrNull()?.coerceIn(2_000L, 120_000L) ?: 30_000L
        val baselineStartedNanos = SystemClock.elapsedRealtimeNanos()
        val fixes = LinkedBlockingQueue<Location>()
        val warmListener = LocationListener { location ->
            if (location.elapsedRealtimeNanos >= baselineStartedNanos) fixes.offer(Location(location))
        }
        var registrationAttempted = false
        var publicationAttempted = false
        try {
            // Instrumentation restarts the app, ending its UI location requests. Keep a real GPS
            // stream alive so the independent current consumer can use a fresh provider fix.
            registrationAttempted = true
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1_000L, 0f, { task -> task.run() }, warmListener,
            )
            assumeTrue("GPS supplied no fresh continuous baseline fix",
                fixes.poll(baselineMillis, TimeUnit.MILLISECONDS) != null)
            // The continuous callback above is the real-provider baseline. The independent
            // current-location request below is the behavior under test and must fail loudly if
            // it does not deliver after simulation starts.
            val outgoing = sample(31.230416, 121.473701)
            publicationAttempted = true
            assertTrue("Sample command failed", MockServiceHelper.publishSample(manager, outgoing))
            assertActive(outgoing)
            val received = boundedCurrentFix()
            assertActive(outgoing)
            assertSample(outgoing, received, "GPS current callback")
        } finally {
            try {
                if (registrationAttempted) manager.removeUpdates(warmListener)
            } finally {
                if (publicationAttempted) {
                    assertTrue("Stop command failed", MockServiceHelper.tryCloseMock(manager))
                    val stopped = requireStatus()
                    assertTrue("Stop response has no start state", stopped.containsKey("is_start"))
                    assertFalse("Simulation remained active after cleanup", stopped.getBoolean("is_start"))
                }
            }
        }
    }

    private fun boundedCurrentFix(durationMillis: Long = 2_000L): Location? {
        val cancellation = CancellationSignal()
        val delivered = CountDownLatch(1)
        val received = AtomicReference<Location?>()
        return try {
            manager.getCurrentLocation(
                LocationManager.GPS_PROVIDER,
                LocationRequest.Builder(1_000L)
                    .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                    .setDurationMillis(durationMillis)
                    .build(),
                cancellation,
                { task -> task.run() },
            ) { location ->
                received.set(location?.let { Location(it) })
                delivered.countDown()
            }
            val callbackArrived = delivered.await(durationMillis + 1_000L, TimeUnit.MILLISECONDS)
            android.util.Log.i("PortalDeviceTest",
                "GPS current request: callback=$callbackArrived nonNull=${received.get() != null}")
            if (callbackArrived) received.get() else null
        } finally {
            cancellation.cancel()
        }
    }

    private fun connectAndReadStatus(): Bundle {
        // Reuse the app's client, since a new handshake invalidates the prior session for this UID.
        if (!MockServiceHelper.isServiceInit()) MockServiceHelper.tryInitService(manager)
        assertTrue("Authenticated controller handshake failed", MockServiceHelper.isServiceInit())
        return requireStatus()
    }

    private fun requireStatus(): Bundle {
        val status = MockServiceHelper.runtimeStatus(manager)
        assertNotNull("No authenticated runtime response", status)
        val reply = checkNotNull(status)
        assertEquals(
            "Expected the system-server backend",
            "SYSTEM_SERVER",
            reply.getString("hook_scope"),
        )
        assertTrue("No installed system hooks reported", reply.getInt("hook_installed") > 0)
        return reply
    }

    private fun assertActive(sample: LocationSample) {
        val status = requireStatus()
        assertTrue("Published sample did not start the backend", status.getBoolean("is_start"))
        assertEquals(
            "Backend did not accept this sample",
            sample.timeMillis,
            status.getLong("last_publish"),
        )
    }

    private fun sample(latitude: Double, longitude: Double) =
        LocationSample(
            coordinate = Wgs84(latitude, longitude),
            altitude = 42.0,
            accuracy = 5f,
            speed = 0f,
            bearing = 73f,
            timeMillis = System.currentTimeMillis(),
            elapsedNanos = SystemClock.elapsedRealtimeNanos(),
        )

    private fun awaitSample(queue: LinkedBlockingQueue<Location>, elapsedNanos: Long): Location? {
        val deadline = SystemClock.elapsedRealtime() + 2_000
        while (true) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) return null
            val location = queue.poll(remaining, TimeUnit.MILLISECONDS) ?: return null
            if (location.elapsedRealtimeNanos == elapsedNanos) return location
        }
    }

    private fun assertSample(expected: LocationSample, location: Location?, surface: String) {
        assertNotNull("No sample from $surface", location)
        val actual = checkNotNull(location)
        assertEquals("Wrong provider at $surface", LocationManager.GPS_PROVIDER, actual.provider)
        assertEquals(
            "Wrong sample timestamp at $surface",
            expected.elapsedNanos,
            actual.elapsedRealtimeNanos,
        )
        assertEquals("Wrong wall clock at $surface", expected.timeMillis, actual.time)
        val distance = FloatArray(1)
        Location.distanceBetween(
            expected.latitude,
            expected.longitude,
            actual.latitude,
            actual.longitude,
            distance,
        )
        // Existing optional physical noise can offset coordinates by a few meters; retain that
        // setting.
        assertTrue("Unexpected coordinate at $surface (offset ${distance[0]}m)", distance[0] <= 10f)
        assertTrue(
            "Invalid accuracy at $surface",
            actual.hasAccuracy() && actual.accuracy.isFinite() && actual.accuracy > 0f,
        )
    }

    private fun hasPermission(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
