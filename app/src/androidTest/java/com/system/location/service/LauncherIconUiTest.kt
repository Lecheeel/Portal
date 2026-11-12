package com.system.location.service

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.ui.settings.LauncherIcon
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherIconUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun iconDraftCancelApplyRecreateAndLauncherEntryStayConsistent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = LauncherIcon.current(context)
        fun assertEntry(icon: LauncherIcon) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
            val entries = context.packageManager.queryIntentActivities(intent, 0)
            assertEquals("Exactly one desktop entry", 1, entries.size)
            assertEquals(icon.component(context).className, entries.single().activityInfo.name)
            assertTrue(context.packageManager.getActivityInfo(icon.component(context), 0).loadIcon(context.packageManager).intrinsicWidth > 0)
        }
        try {
            LauncherIcon.select(context, LauncherIcon.STANDARD)
            acceptSelectedMapForUiTest()
            grantUiPermissions()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.openPage(scenario, R.id.nav_settings_updates)
                compose.onNodeWithText("应用图标").performClick()
                compose.onNodeWithText("霓虹版").performClick()
                scenario.recreate()
                compose.onNode(hasText("霓虹版") and isSelectable()).assertIsSelected()
                compose.onNodeWithText("取消").performClick()
                assertEntry(LauncherIcon.STANDARD)
                compose.onNodeWithText("应用图标").performClick()
                compose.onNodeWithText("霓虹版").performClick()
                compose.onNode(hasText("霓虹版") and isSelectable()).assertIsSelected()
                android.os.SystemClock.sleep(350)
                captureUi("launcher-icon-picker")
                compose.onNodeWithText("应用", useUnmergedTree = true).performClick()
                compose.waitForIdle()
                assertEntry(LauncherIcon.NEON)
                scenario.recreate()
                compose.onNodeWithText("霓虹版").assertIsDisplayed()
                compose.onNodeWithText("应用图标").performClick()
                compose.onNodeWithText("普通版").performClick()
                compose.onNodeWithText("应用", useUnmergedTree = true).performClick()
                compose.waitForIdle()
                assertEntry(LauncherIcon.STANDARD)
                scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                compose.waitUntil(5_000) {
                    var landscape = false
                    scenario.onActivity { landscape = it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
                    landscape
                }
                compose.onNodeWithText("应用图标").performScrollTo().performClick()
                compose.onNode(hasText("霓虹版") and isSelectable()).performScrollTo().performClick()
                compose.onNodeWithText("应用", useUnmergedTree = true).assertIsDisplayed()
                compose.onNodeWithText("取消").assertIsDisplayed().performClick()
                assertEntry(LauncherIcon.STANDARD)
                scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            }
            // Launch the enabled alias, not only the explicit MainActivity test entry.
            LauncherIcon.select(context, LauncherIcon.NEON)
            ActivityScenario.launch<MainActivity>(Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER).setComponent(LauncherIcon.NEON.component(context))).use {
                compose.onNodeWithText("开始模拟").assertIsDisplayed()
            }
        } finally { LauncherIcon.select(context, original) }
    }
}
