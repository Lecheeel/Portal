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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EnvironmentPreferencesUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun readableSourceChoicesKeepStoredKeysAndSelectionAcrossRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = context.environmentSourceMode
        acceptSelectedMapForUiTest()
        grantUiPermissions()
        try {
            context.environmentSourceMode = "AUTO"
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.openPage(scenario, R.id.nav_environment)
                compose.scrollToText(context.getString(R.string.environment_source_value, "自动选择")).performClick()
                compose.onNodeWithText("按位置生成").performClick()
                assertEquals("PROCEDURAL", context.environmentSourceMode)
                compose.scrollToText(context.getString(R.string.environment_source_value, "按位置生成")).performClick()
                compose.onNodeWithText("按位置生成").assertIsSelected()
                scenario.recreate()
                compose.onNodeWithText("按位置生成").assertIsSelected()
                compose.onNodeWithText("自动选择").performClick()
                assertEquals("AUTO", context.environmentSourceMode)
            }
        } finally { context.environmentSourceMode = original }
    }

    @Test fun systemSettingsOpensEnvironmentAndReturnsToItsCategory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        acceptSelectedMapForUiTest()
        grantUiPermissions()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.openPage(scenario, R.id.nav_settings_system)
            compose.scrollToText("环境工具").performClick()
            compose.scrollToText(context.getString(R.string.environment_capture_action)).assertIsDisplayed()
            compose.scrollToText(context.getString(R.string.wifi_fingerprint_enable)).assertIsDisplayed()
            captureUi("settings-environment-groups")
            scenario.recreate()
            compose.scrollToText(context.getString(R.string.environment_capture_action)).assertIsDisplayed()
            scenario.onActivity { it.onSupportNavigateUp() }
            compose.scrollToText("SIM 就绪").assertIsDisplayed()
            compose.scrollToText("环境工具").assertIsDisplayed()
        }
    }

    @Test fun replayOverridesProceduralSelectionAndUpdatesVisibleSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = context.environmentSourceMode
        val replay = context.environmentReplayJson
        val apCount = context.environmentApCount
        val name = "ui-replay-${UUID.randomUUID()}"
        val store = EnvironmentStore(context)
        val profile = EnvironmentProfile(source = EnvironmentSourceType.REPLAY, origin = Wgs84(30.0, 120.0),
            generatedAtMillis = 1, seed = 42,
            wifi = listOf(WifiAp("sample", "A4:50:46:12:34:56", -55, 2412, 1)))
        acceptSelectedMapForUiTest()
        grantUiPermissions()
        try {
            store.save(name, profile)
            context.environmentSourceMode = "PROCEDURAL"
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.openPage(scenario, R.id.nav_mock)
                compose.onNodeWithText("位置").assertIsSelected()
                compose.scrollToText("环境工具").performClick()
                compose.scrollToText(context.getString(R.string.environment_source_value, "按位置生成")).assertIsDisplayed()
                compose.scrollToText(context.getString(R.string.environment_list_action)).performClick()
                compose.waitUntil(5_000) { compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(name).performClick()
                compose.onNodeWithText(context.getString(R.string.environment_replay)).performClick()
                compose.waitUntil(5_000) { context.environmentSourceMode == "REPLAY" }
                compose.scrollToText(context.getString(R.string.environment_source_value, "已采集环境回放")).assertIsDisplayed()
                assertEquals(profile, EnvironmentProfileCodec.decode(context.environmentReplayJson))
                scenario.onActivity { it.environmentApCount = 7 }
                compose.scrollToText(context.getString(R.string.environment_ap_value, 7)).assertIsDisplayed()
            }
        } finally {
            context.environmentSourceMode = source
            context.environmentReplayJson = replay
            context.environmentApCount = apCount
            store.delete(name)
        }
    }
}
