package com.system.location.service

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import com.system.location.service.core.runtime.RouteEndPosition
import com.system.location.service.ext.routeEndPosition
import com.system.location.service.ext.sharedPrefs
import com.system.location.service.gnss.gnssReplayOnStart
import com.system.location.service.gnss.gnssReplayProblem
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReplayPreferencesDeviceTest {
    @Test fun routeEndPositionDefaultsToEndpointAndMigratesLegacySwitch() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "route-end-position-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(target) {
            override fun getSharedPreferences(ignored: String, mode: Int) =
                target.getSharedPreferences(name, Context.MODE_PRIVATE)
        }
        try {
            assertEquals(RouteEndPosition.END, isolated.routeEndPosition)
            isolated.sharedPrefs.edit().putBoolean("holdAtRouteEnd", false).commit()
            assertEquals(RouteEndPosition.REAL_LOCATION, isolated.routeEndPosition)
            isolated.sharedPrefs.edit().putBoolean("holdAtRouteEnd", true).commit()
            assertEquals(RouteEndPosition.END, isolated.routeEndPosition)
            isolated.routeEndPosition = RouteEndPosition.START
            assertEquals(RouteEndPosition.START, isolated.routeEndPosition)
        } finally { target.deleteSharedPreferences(name) }
    }

    @Test fun missingAndExpiredSourcesFailOnlyWhenReplayIsSelected() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(target.cacheDir.toPath(), "replay-readiness-").toFile()
        val name = "replay-readiness-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(target) {
            override fun getNoBackupFilesDir() = directory
            override fun getSharedPreferences(ignored: String, mode: Int) = target.getSharedPreferences(name, Context.MODE_PRIVATE)
        }
        try {
            isolated.sharedPrefs.edit().putString("gnssSimulationMode", "REPLAY").commit()
            assertNull(isolated.gnssReplayProblem())
            isolated.gnssReplayOnStart = true
            assertNotNull(isolated.gnssReplayProblem())
            val now = System.currentTimeMillis()
            // Storage policy fixture, isolated from the production receiver evidence.
            val capture = GnssCapture(deviceModel = "readiness fixture", osVersion = "test",
                startedAtMillis = now - 60_000, endedAtMillis = now,
                frames = (0..60).map { index -> GnssObservationFrame(now - 60_000 + index * 1000L,
                    observer = Wgs84(30.0, 120.0), satellites = listOf(
                        GnssSatellite(Constellation.GPS, 1, 25f, 120f, 32f, true, true, true))) })
            val store = GnssCaptureStore(directory.resolve("gnss/receiver-capture.json"))
            store.save(capture)
            assertNull(isolated.gnssReplayProblem())
            val shift = GnssCaptureValidator.MAX_AGE_MILLIS + 60_000
            store.save(capture.copy(startedAtMillis = capture.startedAtMillis - shift,
                endedAtMillis = capture.endedAtMillis - shift,
                frames = capture.frames.map { it.copy(capturedAtMillis = it.capturedAtMillis - shift) }))
            assertNotNull(isolated.gnssReplayProblem())
            isolated.sharedPrefs.edit().putString("gnssSimulationMode", "AUTONOMOUS").commit()
            assertNull(isolated.gnssReplayProblem())
        } finally { directory.deleteRecursively(); target.deleteSharedPreferences(name) }
    }
}
