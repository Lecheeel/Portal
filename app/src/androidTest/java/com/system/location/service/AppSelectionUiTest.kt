package com.system.location.service

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.ext.sensorTargetPackages
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSelectionUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun savedPackagesSurviveSearchRecreationAndCancelUntilExplicitSave() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = context.sensorTargetPackages
        val hiddenPackage = "dev.portal.saved.hidden"
        acceptSelectedMapForUiTest()
        grantUiPermissions()
        try {
            context.sensorTargetPackages = setOf(hiddenPackage)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.openPage(scenario, R.id.nav_settings_apps)
                compose.scrollToText("选择应用").performClick()
                compose.onNodeWithText("搜索应用名称或包名").performTextInput(hiddenPackage)
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText(hiddenPackage).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNode(hasText(hiddenPackage) and isToggleable()).assertIsDisplayed()
                captureUi("settings-app-selector")
                compose.onNodeWithText("清空选择").performClick()
                scenario.recreate()
                compose.onNodeWithText("保存选择（0）").assertIsDisplayed()
                assertEquals(setOf(hiddenPackage), context.sensorTargetPackages)
                scenario.onActivity { it.onSupportNavigateUp() }
                compose.scrollToText("选择应用").performClick()
                compose.onNodeWithText("保存选择（1）").assertIsDisplayed()
                compose.onNodeWithText("清空选择").performClick()
                compose.waitUntil(10_000) {
                    runCatching { compose.onNodeWithText("保存选择（0）").assertIsEnabled(); true }.getOrDefault(false)
                }
                compose.onNodeWithText("保存选择（0）").performClick()
                compose.scrollToText("选择应用").assertIsDisplayed()
                assertEquals(emptySet<String>(), context.sensorTargetPackages)
            }
        } finally { context.sensorTargetPackages = original }
    }
}
