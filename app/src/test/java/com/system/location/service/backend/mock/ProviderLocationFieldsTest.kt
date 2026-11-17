package com.system.location.service.backend.mock

import android.app.Application
import android.os.Build
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.providers.ProviderFieldMask
import com.system.location.service.core.providers.ProviderKind
import com.system.location.service.core.providers.ProviderMixer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercise Android Location itself, including SDKs that lack the newer removal/MSL APIs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 32, 33, 34], manifest = Config.NONE, application = Application::class)
class ProviderLocationFieldsTest {
    private val sample = LocationSample(Wgs84(31.0, 121.0), 20.0, 5f, 2f, 90f,
        10_000L, 10_000_000_000L)
    private val gps = ProviderMixer().mix(sample).getValue(ProviderKind.GPS)

    @Test fun movingGpsRetainsCoordinatesTimingAndOptionalValues() {
        val location = gps.toLocation("gps")
        assertEquals("gps", location.provider)
        assertEquals(gps.sample.latitude, location.latitude, 0.0)
        assertEquals(gps.sample.longitude, location.longitude, 0.0)
        assertEquals(gps.sample.timeMillis, location.time)
        assertEquals(gps.sample.elapsedNanos, location.elapsedRealtimeNanos)
        assertEquals(gps.sample.accuracy, location.accuracy, 0f)
        assertTrue(location.hasAltitude())
        assertEquals(gps.sample.altitude, location.altitude, 0.0)
        assertTrue(location.hasSpeed())
        assertEquals(gps.sample.speed, location.speed, 0f)
        assertTrue(location.hasBearing())
        assertEquals(gps.sample.bearing, location.bearing, 0f)
        assertTrue(location.hasVerticalAccuracy())
        assertEquals(gps.verticalAccuracyMeters!!, location.verticalAccuracyMeters, 0f)
        assertTrue(location.hasSpeedAccuracy())
        assertTrue(location.hasBearingAccuracy())
        assertTrue(location.hasElapsedRealtimeUncertaintyNanos())
        if (Build.VERSION.SDK_INT >= 34) assertFalse(location.hasMslAltitude())
    }

    @Test fun stationaryAndNetworkSamplesLeaveMotionFieldsAbsent() {
        for (view in ProviderMixer().mix(sample.copy(speed = 0f)).values) {
            val location = view.toLocation(view.profile.kind.name.lowercase())
            assertFalse(location.hasSpeed())
            assertFalse(location.hasBearing())
            assertFalse(location.hasSpeedAccuracy())
            assertFalse(location.hasBearingAccuracy())
        }
        val network = ProviderMixer().mix(sample).getValue(ProviderKind.NETWORK).toLocation("network")
        assertFalse(network.hasAltitude())
        assertFalse(network.hasSpeed())
        assertFalse(network.hasBearing())
    }

    @Test fun absentFieldsNeverLeakFromPreviousPublication() {
        val populated = gps.toLocation("fused")
        val empty = gps.copy(fieldsMask = 0).toLocation("fused")
        assertNotSame(populated, empty)
        assertFalse(empty.hasAltitude())
        assertFalse(empty.hasSpeed())
        assertFalse(empty.hasBearing())
        assertFalse(empty.hasVerticalAccuracy())
        assertFalse(empty.hasSpeedAccuracy())
        assertFalse(empty.hasBearingAccuracy())
        assertFalse(empty.hasElapsedRealtimeUncertaintyNanos())
        if (Build.VERSION.SDK_INT >= 34) assertFalse(empty.hasMslAltitude())
    }

    @Test fun mslAltitudeRequiresBothPlatformSupportAndFieldPresence() {
        val msl = gps.copy(fieldsMask = gps.fieldsMask or ProviderFieldMask.MSL_ALTITUDE,
            mslAltitudeMeters = 12.5)
        val location = msl.toLocation("gps") // Must also succeed on API 31–33.
        if (Build.VERSION.SDK_INT >= 34) {
            assertTrue(location.hasMslAltitude())
            assertEquals(12.5, location.mslAltitudeMeters, 0.0)
            assertFalse(msl.copy(mslAltitudeMeters = null).toLocation("gps").hasMslAltitude())
            assertFalse(msl.copy(fieldsMask = gps.fieldsMask).toLocation("gps").hasMslAltitude())
        }
        assertEquals(gps.sample.altitude, location.altitude, 0.0)
    }
}
