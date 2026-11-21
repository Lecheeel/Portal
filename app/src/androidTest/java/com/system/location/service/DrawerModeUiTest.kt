package com.system.location.service

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.graphics.toPixelMap
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
    private var previousAnimatorScale: String? = null
    private fun animatorScale(value: String?) {
        val command = if (value == null) "settings delete global animator_duration_scale"
            else "settings put global animator_duration_scale $value"
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
    @org.junit.Before fun enableAnimationForTheEnergyTrackAssertion() {
        previousAnimatorScale = android.provider.Settings.Global.getString(
            InstrumentationRegistry.getInstrumentation().targetContext.contentResolver, "animator_duration_scale")
        // The UI workflow disables animations; this test specifically checks an enabled loop.
        animatorScale("1")
        compose.waitUntil(5_000) { android.animation.ValueAnimator.areAnimatorsEnabled() }
    }
    @org.junit.After fun restoreAnimatorScale() { animatorScale(previousAnimatorScale) }

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
            compose.waitUntil(10_000) {
                if (!compose.mainClock.autoAdvance) compose.mainClock.advanceTimeByFrame()
                ScenarioRuntime.state.value.backend == backend
            }
            assertFalse("Quick selection must never start a scenario", ScenarioRuntime.state.value.isActive)
        }
        try {
            assertTrue(runBlocking { ScenarioRuntime.selectBackend(BackendType.MOCK_PROVIDER).await() })
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.openDrawer() }
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
                compose.onNodeWithText("已知悉并选择").assertIsDisplayed()
                compose.mainClock.autoAdvance = false
                compose.onNodeWithText("已知悉并选择").performClick()
                awaitBackend(BackendType.NATIVE)
                compose.waitForIdle()
                compose.mainClock.advanceTimeBy(600)
                compose.onNodeWithText("MAX").assertDoesNotExist()
                compose.onNodeWithText("快速模式").assertDoesNotExist()
                compose.onNodeWithTag("drawer-mode-slider").assertIsDisplayed()
                captureUi("drawer-native-mode")
                // Still changing well after the old two-sweep animation would have finished.
                compose.mainClock.advanceTimeBy(4_000)
                val first = compose.onNodeWithTag("drawer-mode-slider").captureToImage().toPixelMap()
                compose.mainClock.advanceTimeBy(400)
                val second = compose.onNodeWithTag("drawer-mode-slider").captureToImage().toPixelMap()
                val changed = (0 until first.width step 3).sumOf { x ->
                    (0 until first.height step 3).count { y -> first[x, y] != second[x, y] }
                }
                assertTrue("Native track must keep animating", changed > 10)
                captureUi("drawer-native-energy-loop")
                compose.mainClock.autoAdvance = true
                select(0f)
                awaitBackend(BackendType.MOCK_PROVIDER)
                assertTrue(runBlocking { ScenarioRuntime.selectBackend(BackendType.XPOSED).await() })
                compose.waitForIdle()
                assertEquals(1f, compose.onNodeWithTag("drawer-mode-slider").fetchSemanticsNode()
                    .config[SemanticsProperties.ProgressBarRangeInfo].current)
            }
        } finally {
            compose.mainClock.autoAdvance = true
            assertTrue(runBlocking { ScenarioRuntime.selectBackend(original).await() })
            preferences.edit().apply {
                if (hadOptIn) putBoolean("native_opt_in", optIn) else remove("native_opt_in")
            }.commit()
        }
    }
}
