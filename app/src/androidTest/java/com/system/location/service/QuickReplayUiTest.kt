package com.system.location.service

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.core.environment.*
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.environment.EnvironmentStore
import com.system.location.service.ext.*
import com.system.location.service.gnss.gnssReplayOnStart
import com.system.location.service.gnss.replayEnvironmentName
import com.system.location.service.runtime.ScenarioRuntime
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class QuickReplayUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun mapChoicesSurviveRecreationAndAreSharedByBothPages() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertFalse("Preserve a running simulation", ScenarioRuntime.state.value.isActive)
        val keys = listOf("enableMockWifi", "disableWifiScan", "environmentSourceMode", "environmentReplayJson",
            "replayEnvironmentName", "gnssReplayOnStart", "gnssSimulationMode")
        val original = context.sharedPrefs.all.filterKeys { it in keys }
        val name = "quick-replay-${UUID.randomUUID()}"
        val store = EnvironmentStore(context)
        val profile = EnvironmentProfile(source = EnvironmentSourceType.REPLAY, origin = Wgs84(30.0, 120.0),
            originAccuracyMeters = 10.0, generatedAtMillis = System.currentTimeMillis(), seed = 42,
            wifi = listOf(WifiAp("test-ap", "A4:50:46:12:34:56", -55, 2412, 1)))
        acceptSelectedMapForUiTest()
        grantUiPermissions()
        try {
            store.save(name, profile)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.onNodeWithContentDescription("环境 / 卫星快捷回放").performClick()
                compose.onNodeWithText("回放环境").performClick()
                compose.waitUntil(15_000) { compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(name).performClick()
                compose.waitUntil(5_000) { context.replayEnvironmentName == name }
                assertTrue(context.mockWifiEnabled)
                assertEquals("REPLAY", context.environmentSourceMode)
                assertEquals(profile, EnvironmentProfileCodec.decode(context.environmentReplayJson))
                compose.onNodeWithText("回放环境").performClick()
                compose.onNodeWithText("程序生成").performClick()
                compose.waitUntil(5_000) { context.environmentSourceMode == "PROCEDURAL" }
                assertEquals("", context.environmentReplayJson)
                compose.onNodeWithText("回放环境").performClick()
                compose.onNodeWithText(name).performClick()
                compose.waitUntil(5_000) { context.replayEnvironmentName == name }
                compose.waitUntil(5_000) { compose.onAllNodes(hasText("回放卫星") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("回放卫星").performClick()
                compose.onNodeWithText("目标天空（无需采集）").performClick()
                compose.waitUntil(5_000) { context.gnssReplayOnStart }
                assertEquals("AUTONOMOUS", context.sharedPrefs.getString("gnssSimulationMode", null))
                assertFalse("Choosing replay must not secretly start location", ScenarioRuntime.state.value.isActive)
                scenario.recreate()
                compose.onNodeWithText(name).assertExists()
                compose.onNodeWithText("目标天空").assertExists()
                captureUi("home-quick-replay")
                compose.onNodeWithText("管理环境 / 卫星").performClick()
                compose.waitUntil(5_000) { compose.onAllNodesWithText("环境").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("环境").assertIsSelected()
                compose.onNodeWithText(name).assertExists()
                compose.onNodeWithText("卫星").performClick()
                compose.onNodeWithText("目标天空").assertExists()
                compose.onNodeWithText("真实卫星 · 实时观测").assertDoesNotExist()
                captureUi("satellite-quick-replay")
                compose.onNodeWithText("回放卫星").performClick()
                compose.onNodeWithText("关闭卫星模拟").performClick()
                compose.waitUntil(5_000) { !context.gnssReplayOnStart }
                assertEquals("OFF", context.sharedPrefs.getString("gnssSimulationMode", null))
                scenario.recreate()
                compose.onNodeWithText("关闭").assertExists()
                assertEquals("环境/卫星", context.getString(R.string.simulation_title))
            }
        } finally {
            store.delete(name)
            context.sharedPrefs.edit().apply {
                keys.forEach(::remove)
                original.forEach { (key, value) -> when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                } }
            }.commit()
        }
    }
}
