package com.system.location.service.backend.mock

import android.app.Application
import android.os.Build
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.providers.ProviderFieldMask
import com.system.location.service.core.providers.ProviderKind
import com.system.location.service.core.providers.ProviderMixer
import com.system.location.service.core.runtime.HookConfiguration
import com.system.location.service.core.runtime.RuntimeSnapshot
import com.system.location.service.hook.utils.toLocation
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Tests the Hook converter without activating Xposed or changing global runtime settings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 32, 33, 34], manifest = Config.NONE, application = Application::class)
class HookLocationConversionTest {
    private val sample = LocationSample(Wgs84(31.0, 121.0), 20.0, 5f, 2f, 90f,
        10_000L, 10_000_000_000L)
    private val state = RuntimeSnapshot(configuration = HookConfiguration(gnssEnabled = false))
    private val gps = ProviderMixer().mix(sample).getValue(ProviderKind.GPS)

    @Test fun providerMasksMatchStandardConverterForEveryProviderAndMotionState() {
        for (speed in listOf(0f, 2f)) {
            val fix = sample.copy(speed = speed)
            for (view in ProviderMixer().mix(fix).values) {
                val provider = view.profile.kind.name.lowercase()
                val standard = view.toLocation(provider)
                val hook = view.sample.toLocation(provider, state = state, providerSample = view)
                assertEquals(standard.provider, hook.provider)
                assertEquals(standard.latitude, hook.latitude, 0.0)
                assertEquals(standard.longitude, hook.longitude, 0.0)
                assertEquals(standard.time, hook.time)
                assertEquals(standard.elapsedRealtimeNanos, hook.elapsedRealtimeNanos)
                assertEquals(standard.accuracy, hook.accuracy, 0f)
                assertEquals(standard.hasAltitude(), hook.hasAltitude())
                assertEquals(standard.hasSpeed(), hook.hasSpeed())
                assertEquals(standard.hasBearing(), hook.hasBearing())
                assertEquals(standard.hasVerticalAccuracy(), hook.hasVerticalAccuracy())
                assertEquals(standard.hasSpeedAccuracy(), hook.hasSpeedAccuracy())
                assertEquals(standard.hasBearingAccuracy(), hook.hasBearingAccuracy())
                assertEquals(standard.hasElapsedRealtimeUncertaintyNanos(), hook.hasElapsedRealtimeUncertaintyNanos())
                if (Build.VERSION.SDK_INT >= 34) assertFalse(hook.hasMslAltitude())
            }
        }
    }

    @Test fun emptyMaskLeavesAllOptionalFieldsAbsent() {
        val location = sample.toLocation("network", state = state, providerSample = gps.copy(fieldsMask = 0))
        assertFalse(location.hasAltitude())
        assertFalse(location.hasSpeed())
        assertFalse(location.hasBearing())
        assertFalse(location.hasVerticalAccuracy())
        assertFalse(location.hasSpeedAccuracy())
        assertFalse(location.hasBearingAccuracy())
        assertFalse(location.hasElapsedRealtimeUncertaintyNanos())
        assertNull(location.extras)
    }

    @Test fun mslAltitudeOnlyUsesNativeFieldOnApi34WithPresentNonNullValue() {
        val msl = gps.copy(fieldsMask = gps.fieldsMask or ProviderFieldMask.MSL_ALTITUDE,
            mslAltitudeMeters = 12.5)
        val location = sample.toLocation("gps", state = state, providerSample = msl)
        if (Build.VERSION.SDK_INT >= 34) {
            assertTrue(location.hasMslAltitude())
            assertEquals(12.5, location.mslAltitudeMeters, 0.0)
            assertFalse(sample.toLocation("gps", state = state,
                providerSample = msl.copy(mslAltitudeMeters = null)).hasMslAltitude())
            assertFalse(sample.toLocation("gps", state = state,
                providerSample = msl.copy(fieldsMask = gps.fieldsMask)).hasMslAltitude())
        }
        assertEquals(sample.altitude, location.altitude, 0.0)
    }

    @Test fun conversionWithoutProviderSamplePreservesDefaultsExtrasAndMockPolicy() {
        val location = sample.toLocation(null, state = state)
        assertEquals("gps", location.provider)
        assertTrue(location.hasAltitude())
        assertTrue(location.hasSpeed())
        assertTrue(location.hasBearing())
        assertTrue(location.hasVerticalAccuracy())
        assertTrue(location.hasSpeedAccuracy())
        assertTrue(location.hasBearingAccuracy())
        assertTrue(location.hasElapsedRealtimeUncertaintyNanos())
        assertFalse(location.isMock)
        val extras = requireNotNull(location.extras)
        assertEquals(0, extras.getInt("satellites"))
        assertEquals(0f, extras.getFloat("meanCn0"), 0f)
        assertEquals(0f, extras.getFloat("maxCn0"), 0f)
        assertTrue(sample.toLocation("gps", state = state.copy(
            configuration = state.configuration.copy(hideMock = false))).isMock)
    }
}
