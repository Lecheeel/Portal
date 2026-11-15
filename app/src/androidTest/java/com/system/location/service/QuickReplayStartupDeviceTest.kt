package com.system.location.service

import android.location.LocationManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

@RunWith(AndroidJUnit4::class)
class QuickReplayStartupDeviceTest {
    @Test fun xposedStartsTheSavedCombinationAndHonorsDisablingSatelliteOnRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(LocationManager::class.java)
        runBlocking { ScenarioRuntime.awaitInitialized() }
        assumeFalse("Preserve active runtime", ScenarioRuntime.state.value.isActive)
        MockServiceHelper.tryInitService(manager)
        assumeTrue("Requires an installed system module", MockServiceHelper.isServiceInit())
        assumeFalse("Preserve active system simulation", MockServiceHelper.isMockStart(manager))
        val backend = ScenarioRuntime.state.value.backend
        val preferences = context.sharedPrefs
        val keys = listOf("gnssReplayOnStart", "gnssSimulationMode", "enableMockWifi", "disableWifiScan", "environmentSourceMode")
        val original = preferences.all.filterKeys { it in keys }
        val satellite = MockServiceHelper.isGnssMockStart(manager)
        val wifi = MockServiceHelper.isWifiMockStart(manager)
        val config = context.noBackupFilesDir.resolve("active-config.json")
        val originalConfig = config.takeIf { it.exists() }?.readBytes()
        acceptSelectedMapForUiTest()
        grantUiPermissions()
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                preferences.edit().putBoolean("gnssReplayOnStart", true).putString("gnssSimulationMode", "AUTONOMOUS")
                    .putBoolean("enableMockWifi", true).putBoolean("disableWifiScan", true)
                    .putString("environmentSourceMode", "PROCEDURAL").commit()
                runBlocking {
                    assertTrue(ScenarioRuntime.selectBackend(BackendType.XPOSED).await())
                    val scenario = Scenario("quick-replay-device", "快捷回放验证", point = Wgs84(30.0, 120.0))
                    assertTrue(ScenarioRuntime.start(scenario).await())
                    assertTrue(MockServiceHelper.isGnssMockStart(manager))
                    assertTrue(MockServiceHelper.isWifiMockStart(manager))
                    assertTrue(ScenarioRuntime.stop().await())
                    context.gnssReplayOnStart = false
                    assertTrue(ScenarioRuntime.start(scenario).await())
                    assertFalse(MockServiceHelper.isGnssMockStart(manager))
                    assertTrue(MockServiceHelper.isWifiMockStart(manager))
                    context.gnssReplayOnStart = true
                    assertTrue(ScenarioRuntime.applyReplaySettings().await())
                    assertTrue(MockServiceHelper.isGnssMockStart(manager))
                }
            } finally {
                runBlocking {
                    ScenarioRuntime.stop().await()
                    ScenarioRuntime.selectBackend(backend).await()
                }
                preferences.edit().apply {
                    keys.forEach(::remove)
                    original.forEach { (key, value) -> when (value) {
                        is String -> putString(key, value)
                        is Boolean -> putBoolean(key, value)
                    } }
                }.commit()
                if (originalConfig == null) config.delete() else config.writeBytes(originalConfig)
                MockServiceHelper.putConfig(manager, context)
                if (satellite) MockServiceHelper.startGnssMock(manager) else MockServiceHelper.stopGnssMock(manager)
                if (wifi) MockServiceHelper.startWifiMock(manager) else MockServiceHelper.stopWifiMock(manager)
            }
        }
    }
}
