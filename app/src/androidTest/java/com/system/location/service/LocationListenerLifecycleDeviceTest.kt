package com.system.location.service

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.hook.security.CommandClient
import com.system.location.service.runtime.ScenarioRuntime
import java.util.concurrent.Executor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.*
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in framework round trip; never starts simulation or modifies receiver evidence/settings. */
@RunWith(AndroidJUnit4::class)
class LocationListenerLifecycleDeviceTest {
    @Test fun repeatedRegistrationKeepsOneSubscriptionPerProviderAndUnregisterReleasesIt() = verifyRegistration()

    @Test fun rejectedProviderDoesNotCreateAModuleSubscription() = verifyRegistration(rejectedProvider = true)

    private fun verifyRegistration(rejectedProvider: Boolean = false) = runBlocking {
        assumeTrue("Explicit device run required",
            InstrumentationRegistry.getArguments().getString("libxposedDevice") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ScenarioRuntime.awaitInitialized()
        assumeFalse("Preserve local simulation", ScenarioRuntime.state.value.isActive)
        assumeTrue("Existing precise location permission required",
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        val manager = context.getSystemService(LocationManager::class.java)
        val client = CommandClient()
        assertTrue(client.connect(manager))
        fun command(name: String): Bundle = Bundle().apply {
            putString("command_id", name)
            assertTrue("Command failed: $name", client.send(manager, this))
        }
        val status = command("get_runtime_status")
        assertEquals("SYSTEM_SERVER", status.getString("hook_scope"))
        assumeFalse("Preserve system simulation", status.getBoolean("is_start"))
        fun size() = command("get_listener_size").getInt("size", -1).also { assertTrue(it >= 0) }
        val baseline = size()
        val listener = LocationListener { }
        val executor = Executor { it.run() }
        var verified = false
        try {
            if (rejectedProvider) {
                val error = runCatching {
                    manager.requestLocationUpdates("portal_lifecycle_missing_provider", 1000L, 0f, executor, listener)
                }.exceptionOrNull()
                assertTrue("Framework must reject the unknown provider", error is IllegalArgumentException)
                assertEquals("Rejected registration left a module subscription", baseline, size())
                verified = true
                return@runBlocking
            }
            repeat(20) { manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, executor, listener) }
            assertEquals("Duplicate GPS subscriptions in module registry", baseline + 1, size())
            repeat(5) { manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, executor, listener) }
            assertEquals("One transport must preserve its separate provider subscriptions", baseline + 2, size())
            manager.removeUpdates(listener)
            assertEquals("Explicit unregister did not release all provider subscriptions", baseline, size())
            verified = true
        } finally {
            manager.removeUpdates(listener)
            // Keep the original assertion if Android did not retain a rejected client transport.
            if (verified) assertEquals("Test left listener subscriptions behind", baseline, size())
            assertFalse(command("get_runtime_status").getBoolean("is_start"))
        }
    }
}
