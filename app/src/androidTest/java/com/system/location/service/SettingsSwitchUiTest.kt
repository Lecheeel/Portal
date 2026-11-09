package com.system.location.service

import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.toPixelMap
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

    @Test fun sharedSwitchAnimatesBetweenOffAndOn() {
        com.system.location.service.privacy.SdkPrivacy.acceptMaps()
        grantUiPermissions()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String): String = automation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
        }
        val oldScale = shell("settings get global animator_duration_scale")
        shell("settings put global animator_duration_scale 1")
        try {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val checked = androidx.compose.runtime.mutableStateOf(false)
            scenario.onActivity { activity ->
                activity.setContentView(androidx.compose.ui.platform.ComposeView(activity).apply {
                    setContent {
                        com.system.location.service.designsystem.theme.PortalTheme {
                            com.system.location.service.designsystem.components.PortalSwitchRow(
                                title = "动画测试", checked = checked.value,
                                onCheckedChange = { checked.value = it },
                            )
                        }
                    }
                })
            }
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            try {
                val node = compose.onNode(hasText("动画测试") and isToggleable())
                fun pixels(): Int {
                    val image = node.captureToImage().toPixelMap()
                    var hash = 1
                    // Only the trailing switch, using a primitive accumulator (no boxed pixel list).
                    val left = (image.width - 240).coerceAtLeast(0)
                    for (y in 0 until image.height) for (x in left until image.width) {
                        hash = 31 * hash + image[x, y].hashCode()
                    }
                    return hash
                }
                val off = pixels()
                node.performClick()
                compose.mainClock.advanceTimeBy(32)
                val moving = pixels()
                compose.mainClock.advanceTimeBy(500)
                val on = pixels()
                org.junit.Assert.assertNotEquals("Thumb must move after a tap", off, on)
                org.junit.Assert.assertNotEquals("Animation must have an intermediate frame", moving, on)
                node.assertIsOn()
            } finally { compose.mainClock.autoAdvance = true }
        }
        } finally {
            if (oldScale == "null") shell("settings delete global animator_duration_scale")
            else shell("settings put global animator_duration_scale $oldScale")
        }
    }

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
                if (com.system.location.service.runtime.ScenarioRuntime.state.value.backend ==
                    com.system.location.service.core.backend.BackendType.MOCK_PROVIDER) {
                    compose.scrollToText("物理漂移噪声")
                    compose.onNode(hasText("物理漂移噪声") and isToggleable()).assertIsNotEnabled()
                } else checkSwitch("物理漂移噪声") { context.physicalNoise }
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
