package com.system.location.service

import android.location.LocationManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.android.root.RootEnvironmentProbe
import com.system.location.service.backend.xposed.XposedBackend
import com.system.location.service.core.backend.BackendType
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.scenario.Scenario
import com.system.location.service.ext.sharedPrefs
import com.system.location.service.gnss.gnssReplayOnStart
import com.system.location.service.runtime.ScenarioRuntime
import com.system.location.service.service.MockServiceHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.*
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in rooted system-module test; restores saved satellite choices and never enables Native. */
@RunWith(AndroidJUnit4::class)
class SatelliteDefaultsDeviceTest {
    @Test fun targetSkyStartsWithLocationAndExplicitReplayOrDisablingIsPreserved() = runBlocking {
        assumeTrue("Explicit device run required",
            InstrumentationRegistry.getArguments().getString("libxposedDevice") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ScenarioRuntime.awaitInitialized()
        assumeFalse("Preserve local simulation", ScenarioRuntime.state.value.isActive)
        assumeTrue("Root implementation must be available", RootEnvironmentProbe.isAvailable())
        val manager = context.getSystemService(LocationManager::class.java)
        MockServiceHelper.tryInitService(manager)
        assumeTrue("Requires a running system module", MockServiceHelper.isServiceInit())
        assumeFalse("Preserve system simulation", MockServiceHelper.isMockStart(manager))
        val preferences = context.sharedPrefs
        val keys = listOf("gnssReplayOnStart", "gnssSimulationMode")
        val saved = preferences.all.filterKeys { it in keys }
        val backend = ScenarioRuntime.state.value.backend
        val satellite = MockServiceHelper.isGnssMockStart(manager)
        val wifi = MockServiceHelper.isWifiMockStart(manager)
        val config = context.noBackupFilesDir.resolve("active-config.json")
        val savedConfig = config.takeIf { it.exists() }?.readBytes()
        acceptSelectedMapForUiTest()
        grantUiPermissions()
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                assertTrue(preferences.edit().remove("gnssReplayOnStart").remove("gnssSimulationMode").commit())
                assertTrue(ScenarioRuntime.selectBackend(BackendType.XPOSED).await())
                val scenario = Scenario("satellite-default-device", "默认目标天空验证", point = Wgs84(30.0, 120.0))
                assertTrue(ScenarioRuntime.start(scenario).await())
                assertTrue(context.gnssReplayOnStart)
                assertEquals("AUTONOMOUS", preferences.getString("gnssSimulationMode", null))
                assertTrue("Default satellite simulation did not start", MockServiceHelper.isGnssMockStart(manager))
                assertFalse("Default startup loaded Native", MockServiceHelper.runtimeStatus(manager)!!.getBoolean("native_loaded"))
                assertTrue(ScenarioRuntime.stop().await())

                assertTrue(preferences.edit().putBoolean("gnssReplayOnStart", true)
                    .putString("gnssSimulationMode", "REPLAY").commit())
                // Readiness may reject a missing/expired recording. It must never replace the
                // selected source with target sky to make startup succeed.
                XposedBackend(context).prepare()
                assertTrue(context.gnssReplayOnStart)
                assertEquals("REPLAY", preferences.getString("gnssSimulationMode", null))

                assertTrue(preferences.edit().putBoolean("gnssReplayOnStart", false)
                    .putString("gnssSimulationMode", "OFF").commit())
                assertTrue(ScenarioRuntime.start(scenario).await())
                assertFalse("Explicit disabling was overwritten", MockServiceHelper.isGnssMockStart(manager))
            } finally {
                ScenarioRuntime.stop().await()
                ScenarioRuntime.selectBackend(backend).await()
                preferences.edit().apply {
                    keys.forEach(::remove)
                    saved.forEach { (key, value) -> when (value) {
                        is String -> putString(key, value)
                        is Boolean -> putBoolean(key, value)
                    } }
                }.commit()
                if (savedConfig == null) config.delete() else config.writeBytes(savedConfig)
                MockServiceHelper.putConfig(manager, context)
                if (satellite) MockServiceHelper.startGnssMock(manager) else MockServiceHelper.stopGnssMock(manager)
                if (wifi) MockServiceHelper.startWifiMock(manager) else MockServiceHelper.stopWifiMock(manager)
            }
        }
    }
}
