package com.system.location.service

import android.location.LocationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.service.MockServiceHelper
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit fresh-boot acceptance checkpoint; does not alter the selected backend or start a fix. */
@RunWith(AndroidJUnit4::class)
class SensorDailyModeDeviceTest {
    @Test fun dailyXposedModeLeavesNativeUnloadedAndDisabled() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("sensorDailyDevice") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("XPOSED", context.getSharedPreferences("scenario_runtime", 0).getString("backend", null))
        val manager = context.getSystemService(LocationManager::class.java)
        MockServiceHelper.tryInitService(manager)
        val status = checkNotNull(MockServiceHelper.runtimeStatus(manager)) { "System module unavailable" }
        assertEquals("SYSTEM_SERVER", status.getString("hook_scope"))
        assertTrue(status.getInt("hook_installed") > 0)
        assertFalse("Acceptance must start with no active scenario", status.getBoolean("is_start"))
        assertTrue(status.containsKey("native_loaded") && status.containsKey("native_enabled"))
        assertFalse("Daily mode loaded the optional library", status.getBoolean("native_loaded"))
        assertFalse("Daily mode enabled Native", status.getBoolean("native_enabled"))
    }
}
