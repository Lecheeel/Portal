package com.system.location.service

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.system.location.service.ui.displayLabel
import com.system.location.service.core.backend.BackendType
import com.system.location.service.runtime.ScenarioRuntime
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SimulationNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Before fun consent() {
        com.system.location.service.privacy.SdkPrivacy.acceptMaps()
        grantUiPermissions()
    }

    @Test fun combinedTabsKeepCoordinatesAndRestoreSelectedTab() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.openPage(scenario, R.id.nav_mock)
            compose.waitUntil(5_000) { compose.onAllNodesWithText("位置名称").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("位置名称").assertIsDisplayed()
            scenario.onActivity { assertNull(it.binding.navView.menu.findItem(R.id.nav_gnss_mock)) }
            compose.onNodeWithText("纬度 · -90 至 90").performTextReplacement("26.123456")
            compose.onNodeWithText("卫星").performClick()
            compose.onNodeWithText("卫星").assertIsSelected()
            compose.onNodeWithText("星图信息").assertIsDisplayed()
            scenario.recreate()
            compose.onNodeWithText("卫星").assertIsSelected()
            compose.onNodeWithText("星图信息").assertIsDisplayed()
            compose.onNodeWithText("位置").performClick()
            compose.onNodeWithText("位置").assertIsSelected()
            compose.onNodeWithText("纬度 · -90 至 90").assertTextContains("26.123456")
        }
    }

    @Test fun cancellingNativeSelectionKeepsActualBackendAcrossRecreation() {
        val original = ScenarioRuntime.state.value.backend
        assertFalse("Run on an idle fixture", ScenarioRuntime.state.value.isActive)
        assertNotEquals("Run on an idle non-Native fixture", BackendType.NATIVE, original)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.openPage(scenario, R.id.nav_runtime)
            compose.scrollToText(BackendType.NATIVE.displayLabel()).performClick()
            compose.scrollToText("应用模式").performClick()
            compose.onNodeWithText("实验性模式风险须知").assertIsDisplayed()
            assertEquals(original, ScenarioRuntime.state.value.backend)
            scenario.recreate()
            compose.onNodeWithText("实验性模式风险须知").assertIsDisplayed()
            assertEquals(original, ScenarioRuntime.state.value.backend)
            compose.onNodeWithText("取消").performClick()
            compose.onNodeWithText("实验性模式风险须知").assertDoesNotExist()
            assertEquals(original, ScenarioRuntime.state.value.backend)
            compose.scrollToText(original.displayLabel()).assertIsSelected()
            compose.onNodeWithText(BackendType.NATIVE.displayLabel()).assertIsNotSelected()
        }
    }
}
