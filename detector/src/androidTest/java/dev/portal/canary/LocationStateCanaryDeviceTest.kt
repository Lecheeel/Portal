package dev.portal.canary

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Reproduces known state/coarse-current failures; host owns all state changes. */
class LocationStateCanaryDeviceTest : CanaryDeviceFixture() {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager get() = context.getSystemService(LocationManager::class.java)
    private fun report(name: String, vararg values: Pair<String, Any?>) {
        val json = JSONObject().put("check", name).put("uid", android.os.Process.myUid())
        values.forEach { (key, value) -> json.put(key, value ?: JSONObject.NULL) }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("canary", json.toString())
        })
    }

    @Test fun disabledGpsDoesNotDeliver() = disabledProvider("gps")
    @Test fun disabledNetworkDoesNotDeliver() = disabledProvider("network")

    private fun disabledProvider(provider: String) {
        assertFalse("Host must disable $provider", manager.isProviderEnabled(provider))
        val events = java.util.concurrent.atomic.AtomicInteger()
        val listener = LocationListener { events.incrementAndGet() }
        try {
            manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            Thread.sleep(3500)
        } finally { manager.removeUpdates(listener) }
        report("disabledProvider", "provider" to provider, "callbacks" to events.get())
        assertEquals("Disabled $provider delivered updates", 0, events.get())
    }

    private fun coarsePermission() {
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION))
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    @Test fun coarseLast() {
        coarsePermission()
        checkCoarse("last", manager.getLastKnownLocation("gps"))
    }

    @Test fun coarseCurrent() {
        coarsePermission()
        val latch = CountDownLatch(1)
        val signal = android.os.CancellationSignal()
        var current: Location? = null
        try {
            val provider = InstrumentationRegistry.getArguments().getString("currentProvider", "gps")
            manager.getCurrentLocation(provider, signal, context.mainExecutor) { current = it; latch.countDown() }
            val received = latch.await(15, TimeUnit.SECONDS)
            report("coarseCurrentRequest", "requestedProvider" to provider, "callbackReceived" to received)
            assertTrue("Current fix timed out", received)
            checkCoarse("current", current)
        } finally { signal.cancel() }
    }

    @Test fun coarseUpdate() {
        coarsePermission()
        val updateLatch = CountDownLatch(1)
        var update: Location? = null
        val listener = LocationListener { update = it; updateLatch.countDown() }
        try {
            manager.requestLocationUpdates("gps", 0L, 0f, listener, Looper.getMainLooper())
            assertTrue("Update timed out", updateLatch.await(8, TimeUnit.SECONDS))
            checkCoarse("update", update)
        } finally { manager.removeUpdates(listener) }
    }

    private fun checkCoarse(api: String, location: Location?) {
        report("coarse", "api" to api, "provider" to location?.provider, "accuracy" to location?.accuracy)
        assertNotNull("No $api location", location)
        assertTrue("$api accuracy ${location!!.accuracy}", location.accuracy >= 2000f)
        assertEquals("network", location.provider)
    }
}
