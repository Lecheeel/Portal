package com.system.location.service

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.core.view.GravityCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.core.backend.BackendType
import com.system.location.service.runtime.ScenarioRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DrawerModeUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun sliderSelectsThreeModesAndNativeRequiresExplicitConfirmationAcrossRecreation() {
        acceptSelectedMapForUiTest()
        grantUiPermissions()
        assertFalse(ScenarioRuntime.state.value.isActive)
        val original = ScenarioRuntime.state.value.backend
        val preferences = InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("scenario_runtime", 0)
        val hadOptIn = preferences.contains("native_opt_in")
        val optIn = preferences.getBoolean("native_opt_in", false)
        fun select(position: Float) {
            compose.onNodeWithTag("drawer-mode-slider").assertIsEnabled()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(position) }
        }
        fun awaitBackend(backend: BackendType) {
            compose.waitUntil(10_000) { ScenarioRuntime.state.value.backend == backend }
            assertFalse("Quick selection must never start a scenario", ScenarioRuntime.state.value.isActive)
        }
        try {
            assertTrue(runBlocking { ScenarioRuntime.selectBackend(BackendType.MOCK_PROVIDER).await() })
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.binding.drawerLayout.openDrawer(GravityCompat.START, false) }
                select(1f)
                awaitBackend(BackendType.XPOSED)
                select(2f)
                compose.onNodeWithText("实验性模式风险须知").assertIsDisplayed()
                assertEquals(BackendType.XPOSED, ScenarioRuntime.state.value.backend)
                scenario.recreate()
                compose.onNodeWithText("实验性模式风险须知").assertIsDisplayed()
                compose.onNodeWithText("取消").performClick()
                compose.onNodeWithTag("drawer-mode-slider").assertIsDisplayed()
                assertEquals(1f, compose.onNodeWithTag("drawer-mode-slider").fetchSemanticsNode()
                    .config[SemanticsProperties.ProgressBarRangeInfo].current)
                select(2f)
                compose.onNodeWithText("已知悉并选择").performClick()
                awaitBackend(BackendType.NATIVE)
                compose.onNodeWithText("MAX").assertIsDisplayed()
                captureUi("drawer-native-mode")
                select(0f)
                awaitBackend(BackendType.MOCK_PROVIDER)
                assertTrue(runBlocking { ScenarioRuntime.selectBackend(BackendType.XPOSED).await() })
                compose.waitForIdle()
                assertEquals(1f, compose.onNodeWithTag("drawer-mode-slider").fetchSemanticsNode()
                    .config[SemanticsProperties.ProgressBarRangeInfo].current)
            }
        } finally {
            assertTrue(runBlocking { ScenarioRuntime.selectBackend(original).await() })
            preferences.edit().apply {
                if (hadOptIn) putBoolean("native_opt_in", optIn) else remove("native_opt_in")
            }.commit()
        }
    }
}
