package com.system.location.service

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.system.location.service.ui.displayLabel
import com.system.location.service.core.backend.BackendType
import com.system.location.service.runtime.ScenarioRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SimulationNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Before fun consent() {
        acceptSelectedMapForUiTest()
        grantUiPermissions()
    }

    @Test fun environmentAndSatelliteTabsRestoreSelection() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.openPage(scenario, R.id.nav_mock)
            compose.onNodeWithText("环境").assertIsSelected()
            compose.onNodeWithText("手动新建").assertIsDisplayed()
            scenario.onActivity { assertFalse(com.system.location.service.ui.navigation.portalDestinations.any { target -> target.id == R.id.nav_gnss_mock }) }
            compose.onNodeWithText("卫星").performClick()
            compose.onNodeWithText("卫星").assertIsSelected()
            compose.onNodeWithText("星图信息").assertIsDisplayed()
            scenario.recreate()
            compose.onNodeWithText("卫星").assertIsSelected()
            compose.onNodeWithText("星图信息").assertIsDisplayed()
            compose.onNodeWithText("环境").performClick()
            compose.onNodeWithText("环境").assertIsSelected()
            compose.onNodeWithText("手动新建").assertIsDisplayed()
            compose.onNodeWithText("位置名称").assertDoesNotExist()
        }
    }

    @Test fun cancellingNativeSelectionKeepsActualBackendAcrossRecreation() {
        val original = ScenarioRuntime.state.value.backend
        assertFalse("Run on an idle fixture", ScenarioRuntime.state.value.isActive)
        val baseline = BackendType.MOCK_PROVIDER
        assertTrue(runBlocking { ScenarioRuntime.selectBackend(baseline).await() })
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.openPage(scenario, R.id.nav_runtime)
                compose.scrollToText(BackendType.NATIVE.displayLabel()).performClick()
                compose.scrollToText("应用模式").performClick()
                compose.onNodeWithText("实验性模式风险须知").assertIsDisplayed()
                assertEquals(baseline, ScenarioRuntime.state.value.backend)
                scenario.recreate()
                compose.onNodeWithText("实验性模式风险须知").assertIsDisplayed()
                assertEquals(baseline, ScenarioRuntime.state.value.backend)
                compose.onNodeWithText("取消").performClick()
                compose.onNodeWithText("实验性模式风险须知").assertDoesNotExist()
                assertEquals(baseline, ScenarioRuntime.state.value.backend)
                compose.scrollTo(hasText(baseline.displayLabel()) and hasClickAction()).assertIsSelected()
                compose.onNode(hasText(BackendType.NATIVE.displayLabel()) and hasClickAction()).assertIsNotSelected()
            }
        } finally { assertTrue(runBlocking { ScenarioRuntime.selectBackend(original).await() }) }
    }
}
