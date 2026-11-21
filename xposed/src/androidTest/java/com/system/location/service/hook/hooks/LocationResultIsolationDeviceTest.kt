package com.system.location.service.hook.hooks

import android.location.Location
import android.os.Parcel
import android.os.Parcelable
import com.system.location.service.hook.runtime.Reflection
import org.junit.Assert.*
import org.junit.Test

/** Run with hidden API access, as in the injected system hook's platform context. */
class LocationResultIsolationDeviceTest {
    private val type = Class.forName("android.location.LocationResult")
    private val locations = Reflection.findField(type, "mLocations")
    private fun result(latitude: Double): Any = Reflection.callStaticMethod(type, "wrap", listOf(
        Location("gps").apply { this.latitude = latitude; longitude = 121.0; time = 1_000L;
            elapsedRealtimeNanos = 1_000_000L; accuracy = 2f }
    ))!!

    @Test fun platformParcelGetsTheReplacementWhileTheCacheRetainsItsOriginalList() {
        val original = result(30.0)
        val originalList = locations.get(original)
        val replacement = locations.get(result(31.0)) as List<*>
        val parcel = Parcel.obtain()
        try {
            LocationResultIsolation.writeWithLocations(original, locations, { replacement }) {
                (original as Parcelable).writeToParcel(parcel, 0)
            }
            assertSame(originalList, locations.get(original))
            assertEquals(30.0, ((locations.get(original) as List<*>).single() as Location).latitude, 0.0)
            parcel.setDataPosition(0)
            val creator = Reflection.getStaticObjectField(type, "CREATOR") as Parcelable.Creator<*>
            val received = requireNotNull(creator.createFromParcel(parcel))
            assertEquals(31.0, ((locations.get(received) as List<*>).single() as Location).latitude, 0.0)
        } finally { parcel.recycle() }
    }

    @Test fun forwardingBuildsAPlatformResultWithoutChangingTheSource() {
        val original = result(30.0)
        val originalList = locations.get(original)
        val copy = LocationResultIsolation.copyWithLocations(original, locations.get(result(31.0)) as List<*>)
        assertNotSame(original, copy)
        assertSame(originalList, locations.get(original))
        assertNotSame(originalList, locations.get(copy))
        assertEquals(31.0, ((locations.get(copy) as List<*>).single() as Location).latitude, 0.0)
        assertEquals(30.0, ((locations.get(original) as List<*>).single() as Location).latitude, 0.0)
    }
}
