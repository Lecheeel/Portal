package com.system.location.service

import android.content.Context
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.core.backend.BackendType
import com.system.location.service.core.sensors.StepSimulationMethod
import com.system.location.service.data.persistence.StepSimulationPreferences
import com.system.location.service.ext.sharedPrefs
import com.system.location.service.runtime.ScenarioRuntime
import com.system.location.service.ui.mock.RockerOverlay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StepSimulationSettingsUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun methodCadenceAndOverlayShareThePersistedSwitch() {
        acceptSelectedMapForUiTest()
        grantUiPermissions()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = context.sharedPrefs
        val overlayPreferences = context.getSharedPreferences("rocker_overlay", Context.MODE_PRIVATE)
        val overlayEnabled = overlayPreferences.getBoolean("enabled", true)
        val overlayAllowed = Settings.canDrawOverlays(context)
        val originalBackend = ScenarioRuntime.state.value.backend
        val originalSettings = preferences.all.filterKeys { it in StepSimulationPreferences.keys }
        fun stepSettings() = StepSimulationPreferences.read(preferences.all)
        fun overlayPermission(mode: String) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "appops set ${context.packageName} SYSTEM_ALERT_WINDOW $mode")).use { it.readBytes() }
        }
        assertFalse(ScenarioRuntime.state.value.isActive)
        try {
            preferences.edit().apply { StepSimulationPreferences.keys.forEach(::remove) }.commit()
            overlayPermission("allow")
            assertTrue(runBlocking { ScenarioRuntime.selectBackend(BackendType.XPOSED).await() })
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                instrumentation.runOnMainSync { assertTrue(RockerOverlay.setEnabled(true)) }
                compose.openPage(scenario, R.id.nav_settings_motion)
                val methodLabel = context.getString(R.string.step_method)
                val autoLabel = context.getString(R.string.step_auto)
                val enabledLabel = context.getString(R.string.step_enable)
                compose.scrollToText(methodLabel).assertTextContains(context.getString(R.string.step_method_xposed))
                compose.scrollToText(autoLabel).assertIsOn()
                captureUi("step-simulation-settings")
                compose.scrollToText(methodLabel).performClick()
                compose.onNode(hasText(context.getString(R.string.step_method_native)) and hasClickAction()).performClick()
                compose.waitUntil(5_000) { stepSettings().method == StepSimulationMethod.NATIVE }
                compose.scrollToText(autoLabel).performClick()
                compose.waitUntil(5_000) { !stepSettings().automatic }
                compose.scrollToText(context.getString(R.string.step_fixed_cadence)).performClick()
                compose.onNode(hasSetTextAction()).performTextReplacement("221")
                compose.onNodeWithText("保存").performClick()
                compose.onNodeWithText(context.getString(R.string.step_fixed_cadence_range)).assertIsDisplayed()
                compose.onNode(hasSetTextAction()).performTextReplacement("147")
                compose.onNodeWithText("保存").performClick()
                compose.waitUntil(5_000) { stepSettings().manualStepsPerMinute == 147 }
                compose.scrollToText(enabledLabel).assertIsOn()
                compose.onNodeWithTag("rocker_expand").performClick()
                val floatingSwitch = compose.onNodeWithTag("rocker_step_simulation").assertIsOn()
                captureUi("step-simulation-overlay")
                floatingSwitch.performClick()
                compose.waitUntil(5_000) { !stepSettings().enabled }
                compose.onNodeWithText(enabledLabel).assertIsOff().performClick()
                compose.waitUntil(5_000) { stepSettings().enabled }
                floatingSwitch.assertIsOn()
                assertTrue(runBlocking { ScenarioRuntime.selectBackend(BackendType.MOCK_PROVIDER).await() })
                floatingSwitch.assertIsNotEnabled()
                assertFalse(ScenarioRuntime.state.value.isActive)
            }
        } finally {
            instrumentation.runOnMainSync { RockerOverlay.setEnabled(false) }
            overlayPreferences.edit().putBoolean("enabled", overlayEnabled).commit()
            preferences.edit().apply {
                StepSimulationPreferences.keys.forEach { key ->
                    when (val value = originalSettings[key]) {
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                        is String -> putString(key, value)
                        else -> remove(key)
                    }
                }
            }.commit()
            assertTrue(runBlocking { ScenarioRuntime.selectBackend(originalBackend).await() })
            if (!overlayAllowed) overlayPermission("default")
        }
    }
}
