package com.system.location.service

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.ext.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsSwitchUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun preferenceSwitchesUpdateImmediatelyAndCanToggleBackWithoutLeavingPage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        com.system.location.service.privacy.SdkPrivacy.acceptMaps()
        grantUiPermissions()
        val smooth = context.smoothRouteMotion
        val noise = context.physicalNoise
        val burst = context.startupBurstEnabled
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.openPage(scenario, R.id.nav_settings)
                fun checkSwitch(title: String, read: () -> Boolean) {
                    compose.scrollToText(title)
                    val node = compose.onNode(hasText(title) and isToggleable())
                    val before = read()
                    node.assertIsToggleable().assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.ToggleableState,
                        if (before) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off))
                    node.performClick()
                    assertEquals("Preference must change for $title", !before, read())
                    node.assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.ToggleableState,
                        if (!before) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off))
                    node.performClick()
                    assertEquals("Second tap must restore $title", before, read())
                    node.assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.ToggleableState,
                        if (before) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off))
                }
                checkSwitch("路线平滑运动") { context.smoothRouteMotion }
                checkSwitch("物理漂移噪声") { context.physicalNoise }
                checkSwitch("启动突发") { context.startupBurstEnabled }
                scenario.recreate()
                compose.scrollToText("路线平滑运动")
                compose.onNode(hasText("路线平滑运动") and isToggleable()).assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.ToggleableState,
                    if (smooth) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off))
            }
        } finally {
            context.smoothRouteMotion = smooth
            context.physicalNoise = noise
            context.startupBurstEnabled = burst
        }
    }
}
