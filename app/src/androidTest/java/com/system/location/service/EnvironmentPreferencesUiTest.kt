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
        com.system.location.service.privacy.SdkPrivacy.acceptMaps()
        grantUiPermissions()
        try {
            store.save(name, profile)
            context.environmentSourceMode = "PROCEDURAL"
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.openPage(scenario, R.id.nav_mock)
                compose.onNodeWithText("位置").assertIsSelected()
                compose.scrollToText("环境工具").performClick()
                compose.scrollToText(context.getString(R.string.environment_source_value, "PROCEDURAL")).assertIsDisplayed()
                compose.scrollToText(context.getString(R.string.environment_list_action)).performClick()
                compose.waitUntil(5_000) { compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(name).performClick()
                compose.onNodeWithText(context.getString(R.string.environment_replay)).performClick()
                compose.waitUntil(5_000) { context.environmentSourceMode == "REPLAY" }
                compose.scrollToText(context.getString(R.string.environment_source_value, "REPLAY")).assertIsDisplayed()
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
