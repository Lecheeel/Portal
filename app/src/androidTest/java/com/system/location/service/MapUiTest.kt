package com.system.location.service

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.findNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.runtime.ScenarioRuntime
import com.system.location.service.ui.viewmodel.AMapViewModel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MapUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @Before fun fixture() {
        acceptSelectedMapForUiTest()
        grantUiPermissions()
    }

    @Test fun homeHidesCrosshairAndRequiresExplicitLocationConfirmation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(0, instrumentation.targetContext.resources.getIdentifier("ivCrosshair", "id", instrumentation.targetContext.packageName))
            compose.onNodeWithText("开始模拟").assertIsNotEnabled()
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[AMapViewModel::class.java]
                assertNull(model.markedLoc)
                assertFalse(ScenarioRuntime.state.value.isActive)
                model.rememberOriginalLocation(39.9 to 116.4, isMock = false, simulationActive = false)
            }
            scenario.recreate()
            scenario.onActivity { activity -> assertNotNull(ViewModelProvider(activity)[AMapViewModel::class.java].originalLocation) }
            compose.onNodeWithText("开始模拟").assertIsNotEnabled()
            captureUi("home")
        }
    }

    @Test fun runtimeShowsModeAndKeepsDeveloperToolsCollapsed() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.openPage(scenario, R.id.nav_runtime)
            compose.scrollToText("应用模式").assertIsDisplayed()
            // Scroll to each section first; an uncomposed lazy item cannot prove collapse.
            compose.scrollToText("运行详情").assertIsDisplayed()
            compose.onNodeWithTag("runtime-state-details").assertDoesNotExist()
            compose.scrollToText("运行详情").performClick()
            compose.scrollTo(hasTestTag("runtime-state-details")).assertIsDisplayed()
            compose.scrollToText("运行详情").performClick()
            compose.onNodeWithTag("runtime-state-details").assertDoesNotExist()
            compose.scrollToText("开发者工具").assertIsDisplayed()
            compose.onNodeWithText("环境工具").assertDoesNotExist()
            compose.scrollToText("运行日志").assertIsDisplayed()
            compose.onNodeWithText("导出完整诊断日志").assertDoesNotExist()
            captureUi("runtime-overview")
            compose.scrollToText("开发者工具").performClick()
            compose.onNodeWithText("环境工具").assertDoesNotExist()
            compose.scrollToText("运行日志").performClick()
            compose.scrollToText("日志筛选").assertIsDisplayed()
            compose.scrollToText("清空日志").performClick()
            compose.scrollToText("暂无符合条件的日志").assertIsDisplayed()
            scenario.recreate()
            compose.scrollToText("导出完整诊断日志").assertIsDisplayed()
            captureUi("runtime-logs")
        }
    }

    @Test fun manualModesRenderAndFreehandCanBeUndone() {
        val draft = File(instrumentation.targetContext.filesDir, "route_draft.json")
        val original = draft.takeIf { it.exists() }?.readBytes()
        try {
            draft.delete()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.openPage(scenario, R.id.nav_route_edit)
                compose.onNodeWithText("画线").performClick().assertIsSelected()
                compose.onNodeWithText("单指画线", substring = true).assertIsDisplayed()
                compose.onNodeWithText("保存路线").assertIsNotEnabled()
                compose.onNodeWithText("撤销").assertIsNotEnabled()
                compose.onNodeWithText("加点").performClick().assertIsSelected()
                compose.onNodeWithText("依次点击地图加点", substring = true).assertIsDisplayed()
                captureUi("route-points")
            }
        } finally {
            if (original == null) draft.delete() else draft.writeBytes(original)
        }
    }

    @Test fun routeEditorKeepsMapUsableInLargeTextLandscape() {
        val resolver = instrumentation.targetContext.contentResolver
        val oldScale = android.provider.Settings.System.getFloat(resolver, "font_scale", 1f)
        fun fontScale(value: Float) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "settings put system font_scale $value")).use { it.readBytes() }
        }
        try {
            fontScale(1.3f)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    it.findNavController(R.id.nav_host_fragment_content_main).navigate(R.id.nav_route_edit)
                    it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
                compose.waitUntil(10_000) {
                    var ready = false
                    scenario.onActivity { ready = it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
                    ready
                }
                compose.onNodeWithText("搜索地点").assertIsDisplayed()
                compose.onNodeWithText("保存路线").performScrollTo().assertIsDisplayed()
                scenario.onActivity { activity ->
                    val map = activity.window.decorView.descendantsOfType<com.system.location.service.maps.PortalMapView>()
                        .single { it.isAttachedToWindow }
                    val density = activity.resources.displayMetrics.density
                    assertTrue("Map must retain at least 200dp height", map.height >= 200 * density)
                    assertTrue("Map must retain at least 240dp width", map.width >= 240 * density)
                }
                android.os.SystemClock.sleep(600)
                captureUi("route-editor-large-text-landscape")
                scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                compose.waitUntil(10_000) {
                    var ready = false
                    scenario.onActivity { ready = it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
                    ready
                }
                compose.onNodeWithText("保存路线").assertIsDisplayed()
                compose.onNodeWithText("搜索地点").assertIsDisplayed()
            }
        } finally { fontScale(oldScale) }
    }
}
