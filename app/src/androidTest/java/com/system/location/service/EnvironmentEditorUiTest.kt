package com.system.location.service

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.Manifest
import com.system.location.service.environment.EnvironmentStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EnvironmentEditorUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val store get() = EnvironmentStore(InstrumentationRegistry.getInstrumentation().targetContext)

    @Before fun prepare() { acceptSelectedMapForUiTest(); grantUiPermissions() }

    @Test fun manualEnvironmentPersistsAllRadioKindsAndDraftAcrossRecreation() {
        val name = "ui-manual-${UUID.randomUUID()}"
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.openPage(scenario, R.id.nav_route_gallery)
                compose.onNodeWithText("环境").performClick()
                compose.onNodeWithText("手动新建").performClick()
                compose.scrollToText("环境名称").performTextReplacement(name)
                compose.scrollToText("纬度").performTextReplacement("30.0")
                compose.scrollToText("经度").performTextReplacement("120.0")
                compose.scrollToText("精度（米）").performTextReplacement("10")
                compose.scrollToText("添加Wi-Fi").performClick()
                compose.scrollToText("SSID").performTextInput("manual-ap")
                compose.scrollToText("BSSID").performTextInput("A4:50:46:12:34:56")
                scenario.recreate()
                compose.scrollToText("SSID").assertTextContains("manual-ap")
                compose.onNodeWithText("保存 AP").performClick()
                compose.scrollToText("蓝牙").performClick()
                compose.scrollToText("添加蓝牙").performClick()
                compose.scrollToText("地址").performTextInput("C4:50:46:12:34:56")
                compose.onNodeWithText("保存信标").performClick()
                compose.scrollToText("蜂窝").performClick()
                compose.scrollToText("添加蜂窝").performClick()
                compose.onNodeWithText("保存小区").performClick()
                compose.scrollToText("CDMA").performClick()
                compose.scrollToText("添加CDMA").performClick()
                compose.onNodeWithText("保存 CDMA 小区").performClick()
                captureUi("environment-manual-editor")
                compose.onNodeWithText("保存环境").performClick()
                compose.waitUntil(10_000) { name in store.list() }
                compose.waitUntil(5_000) { compose.onAllNodesWithText("搜索已保存环境").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("环境").assertIsSelected()
                val profile = store.load(name)
                assertEquals("manual-ap", profile.wifi.single().ssid)
                assertEquals(1, profile.ble.size)
                assertEquals(1, profile.cells.size)
                assertEquals(1, profile.cdmaCells.size)
                assertEquals(30.0, profile.origin!!.latitude, 0.0)
                assertEquals(10.0, profile.originAccuracyMeters!!, 0.0)
                scenario.recreate()
                compose.onNodeWithText("环境").assertIsSelected()
                compose.onNodeWithText("搜索已保存环境").performTextInput(name)
                compose.onNode(hasText(name) and hasClickAction() and hasSetTextAction().not()).performClick()
                compose.onNodeWithText("编辑环境").performClick()
                compose.scrollToText("manual-ap").assertIsDisplayed()
                compose.onNodeWithText("返回").performClick()
                assertEquals(profile, store.load(name))
            }
        } finally { store.delete(name) }
    }

    @Test fun duplicateApIsRejectedAndCancellingNewEnvironmentDoesNotCreateAFile() {
        val initial = store.list()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.openPage(scenario, R.id.nav_mock)
            compose.onNodeWithText("手动新建").performClick()
            repeat(2) { index ->
                compose.scrollToText("添加Wi-Fi").performClick()
                compose.scrollToText("SSID").performTextInput("ap-$index")
                compose.scrollToText("BSSID").performTextInput("A4:50:46:12:34:56")
                compose.onNodeWithText("保存 AP").performClick()
                if (index == 1) {
                    compose.scrollToText("BSSID 已存在").assertIsDisplayed()
                    compose.onNodeWithText("取消").performClick()
                }
            }
            compose.onNodeWithText("返回").performClick()
            assertEquals(initial, store.list())
        }
    }

    @Test fun captureShowsProgressAndCanBeCancelledFromBothEntrances() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (instrumentation.targetContext.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.BLUETOOTH_SCAN)
        }
        val initial = store.list()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (destination in listOf(R.id.nav_mock, R.id.nav_route_gallery)) {
                compose.openPage(scenario, destination)
                if (destination == R.id.nav_route_gallery) compose.onNodeWithText("环境").performClick()
                compose.onNodeWithText("采集当前环境").performClick()
                compose.onNodeWithText("正在采集").assertIsNotEnabled()
                compose.onNodeWithText("取消采集").performClick()
                compose.waitUntil(5_000) { compose.onAllNodesWithText("已取消采集").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("手动新建").assertIsEnabled()
            }
            assertEquals(initial, store.list())
        }
    }
}
