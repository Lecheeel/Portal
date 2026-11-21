package com.system.location.service

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import org.junit.Rule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.core.gpx.GpxCodec
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.scenario.Route
import com.system.location.service.ext.*
import com.system.location.service.runtime.ScenarioRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SimulationFeaturesTest {
    @get:Rule val compose = createEmptyComposeRule()
    @org.junit.Before fun acceptMapConsentForThisFixture() {
        acceptSelectedMapForUiTest()
    }
    @Test fun androidSaxPreservesSeparatedTracksAndRejectsExternalEntities() {
        val route = Route("r", "测试 & 路线", listOf(Wgs84(1.0, 2.0), Wgs84(1.1, 2.1)))
        val xml = GpxCodec.encode(listOf(route, route.copy(id = "b", name = "第二段")))
        assertEquals(listOf(route.points, route.points), GpxCodec.decode(xml).map { it.points })
        assertThrows(Exception::class.java) { GpxCodec.decode("<!DOCTYPE gpx [<!ENTITY x SYSTEM 'file:///etc/hosts'>]><gpx>&x;</gpx>") }
    }
    @Test fun settingsExposeMotionChannelsAndBackgroundGuidanceAndRuntimeExport() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        grantUiPermissions()
        val old = context.gmsMockEnabled
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.openPage(scenario, R.id.nav_settings_advanced)
                compose.scrollToText("Google 融合定位").assertIsDisplayed()
                compose.onNode(hasText("Google 融合定位") and isToggleable()).performClick()
                assertEquals(!old, context.gmsMockEnabled)
                compose.scrollToText("系统 Fused 通道").assertIsDisplayed()
                compose.scrollToText("启动突发").assertIsDisplayed()
                captureUi("settings-channels")
                compose.openPage(scenario, R.id.nav_settings_motion)
                compose.scrollToText("路线平滑运动").assertIsDisplayed()
                compose.openPage(scenario, R.id.nav_settings_gnss)
                compose.scrollToText("最少卫星数").assertIsDisplayed()
                compose.openPage(scenario, R.id.nav_settings_privacy)
                compose.scrollToText("后台运行").performClick()
                compose.onNodeWithText(context.getString(R.string.background_guidance)).assertIsDisplayed()
                captureUi("background-guidance")
                compose.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
                compose.openPage(scenario, R.id.nav_runtime)
                compose.scrollToText("运行日志").performClick()
                compose.scrollToText("导出完整诊断日志").assertIsDisplayed()
                val report = runBlocking { ScenarioRuntime.diagnosticReport() }
                assertTrue(report.contains(BuildConfig.VERSION_NAME))
                assertTrue(report.contains("Android"))
            }
        } finally { context.gmsMockEnabled = old }
    }
}
