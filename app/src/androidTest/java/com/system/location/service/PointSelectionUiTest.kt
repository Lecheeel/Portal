package com.system.location.service

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.repository.SavedLocation
import com.system.location.service.core.routing.RoutingConfig
import com.system.location.service.core.routing.withPointAssignments
import com.system.location.service.data.repository.LibraryRepositories
import com.system.location.service.ext.routingJson
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PointSelectionUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun searchSurvivesRecreationAndOnlySavingAppsUpdatesPointAssignments() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        acceptSelectedMapForUiTest()
        grantUiPermissions()
        LibraryRepositories.ensureMigrated()
        val originalPoints = LibraryRepositories.locations.list()
        val originalRouting = context.routingJson
        val point = SavedLocation("ui-point-first", "相同地点", Wgs84(25.1, 119.1), "测试地址甲")
        val other = SavedLocation("ui-point-second", "相同地点", Wgs84(26.1, 120.1), "测试地址乙")
        val packageName = "dev.portal.point.hidden"
        val initial = RoutingConfig().withPointAssignments(point.coordinate, setOf(packageName))
            .withPointAssignments(other.coordinate, setOf("dev.portal.other.hidden"))
        try {
            LibraryRepositories.locations.replaceAll(listOf(point, other))
            context.routingJson = Json.encodeToString(RoutingConfig.serializer(), initial)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.openPage(scenario, R.id.nav_settings_apps)
                val entry = compose.scrollToText("分应用固定点（实验）")
                if (com.system.location.service.runtime.ScenarioRuntime.state.value.backend ==
                    com.system.location.service.core.backend.BackendType.MOCK_PROVIDER) {
                    entry.assertIsNotEnabled()
                    compose.openPage(scenario, R.id.nav_point_selection)
                } else entry.performClick()
                compose.onNodeWithText("搜索地点名称或地址").performTextInput("地址甲")
                compose.waitUntil(10_000) { compose.onAllNodesWithText("相同地点").fetchSemanticsNodes().size == 1 }
                scenario.recreate()
                compose.onNodeWithText("搜索地点名称或地址").assertTextContains("地址甲")
                compose.waitUntil(10_000) { compose.onAllNodesWithText("相同地点").fetchSemanticsNodes().size == 1 }
                compose.onNodeWithText("相同地点").performClick()
                compose.onNodeWithText("清空选择").performClick()
                scenario.onActivity { it.onSupportNavigateUp() }
                compose.onNodeWithText("搜索地点名称或地址").assertTextContains("地址甲")
                assertEquals(initial, Json.decodeFromString(RoutingConfig.serializer(), context.routingJson))
                compose.onNodeWithText("相同地点").performClick()
                compose.onNodeWithText("清空选择").performClick()
                compose.waitUntil(10_000) {
                    runCatching { compose.onNodeWithText("保存选择（0）").assertIsEnabled(); true }.getOrDefault(false)
                }
                compose.onNodeWithText("保存选择（0）").performClick()
                compose.scrollToText("分应用固定点（实验）").assertIsDisplayed()
                assertEquals(initial.withPointAssignments(point.coordinate, emptySet()),
                    Json.decodeFromString(RoutingConfig.serializer(), context.routingJson))
            }
        } finally {
            LibraryRepositories.locations.replaceAll(originalPoints)
            context.routingJson = originalRouting
        }
    }

    @Test fun emptyAndCorruptLibrariesHaveDistinctStatesAndRetryRecovers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        acceptSelectedMapForUiTest()
        grantUiPermissions()
        LibraryRepositories.ensureMigrated()
        val originalPoints = LibraryRepositories.locations.list()
        val originalRouting = context.routingJson
        try {
            LibraryRepositories.locations.replaceAll(emptyList())
            context.routingJson = ""
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.openPage(scenario, R.id.nav_point_selection)
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText("还没有保存地点").fetchSemanticsNodes().isNotEmpty()
                }
                java.io.File(context.filesDir, "library/locations.json").writeText("invalid library")
                scenario.recreate()
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText("重新加载").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("还没有保存地点").assertDoesNotExist()
                assertEquals("", context.routingJson)
                LibraryRepositories.locations.replaceAll(listOf(
                    SavedLocation("ui-retry", "重试恢复地点", Wgs84(25.1, 119.1), "验证地址")))
                compose.onNodeWithText("重新加载").performClick()
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText("重试恢复地点").fetchSemanticsNodes().isNotEmpty()
                }
                compose.waitForIdle()
                captureUi("settings-point-selector")
                context.routingJson = "invalid rules"
                scenario.recreate()
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText("重新加载").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("重试恢复地点").assertDoesNotExist()
                assertEquals("invalid rules", context.routingJson)
            }
        } finally {
            LibraryRepositories.locations.replaceAll(originalPoints)
            context.routingJson = originalRouting
        }
    }
}
