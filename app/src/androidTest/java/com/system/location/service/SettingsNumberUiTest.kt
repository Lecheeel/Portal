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
class SettingsNumberUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun invalidInputCanBeCorrectedAndSavedValueAppearsWithoutNavigation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = context.altitude
        acceptSelectedMapForUiTest()
        grantUiPermissions()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.openPage(scenario, R.id.nav_settings_location)
                compose.scrollToText("定位海拔").performClick()
                compose.waitForIdle()
                val editor = compose.onNode(hasSetTextAction())
                editor.performTextReplacement("10001")
                compose.onNodeWithText("保存").performClick()
                editor.assertIsDisplayed()
                compose.onNodeWithText("海拔需在 0–10000 米之间").assertIsDisplayed()
                scenario.recreate()
                editor.assertTextContains("10001")
                compose.onNodeWithText("海拔需在 0–10000 米之间").assertIsDisplayed()
                assertEquals(original, context.altitude, 0.001)
                editor.performTextReplacement("1234.5")
                compose.onNodeWithText("保存").performClick()
                compose.onNodeWithText("%.2f 米".format(1234.5)).assertIsDisplayed()
                assertEquals(1234.5, context.altitude, 0.001)
                scenario.recreate()
                compose.scrollToText("定位海拔")
                compose.onNodeWithText("%.2f 米".format(1234.5)).assertIsDisplayed()
                // Also cover writes from another UI (for example the rocker overlay).
                scenario.onActivity { it.altitude = 4321.0 }
                compose.onNodeWithText("%.2f 米".format(4321.0)).assertIsDisplayed()
            }
        } finally { context.altitude = original }
    }


    @Test fun decimalAccuracyAndIntegerIntervalValidateAndCancelWithoutWriting() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val accuracy = context.accuracy
        val interval = context.reportDuration
        acceptSelectedMapForUiTest()
        grantUiPermissions()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.openPage(scenario, R.id.nav_settings_location)
                compose.scrollToText("定位精度").performClick()
                compose.onNodeWithText("大于 0 且不超过 1000 米").assertIsDisplayed()
                compose.onNode(hasSetTextAction()).performTextReplacement("0")
                compose.onNodeWithText("保存").performClick()
                compose.onNodeWithText("精度需大于 0 且不超过 1000 米").assertIsDisplayed()
                assertEquals(accuracy, context.accuracy, 0f)
                compose.onNode(hasSetTextAction()).performTextReplacement("12.5")
                scenario.recreate()
                compose.onNode(hasSetTextAction()).assertTextContains("12.5")
                compose.onNodeWithText("取消").performClick()
                assertEquals(accuracy, context.accuracy, 0f)
                compose.scrollToText("定位精度").performClick()
                compose.onNode(hasSetTextAction()).assertTextContains(accuracy.toString())
                compose.onNode(hasSetTextAction()).performTextReplacement("12.5")
                compose.onNodeWithText("保存").performClick()
                assertEquals(12.5f, context.accuracy, 0f)

                compose.scrollToText("位置上报间隔").performClick()
                compose.onNodeWithText("50–1000 毫秒，整数").assertIsDisplayed()
                compose.onNode(hasSetTextAction()).performTextReplacement("50.5")
                compose.onNodeWithText("保存").performClick()
                compose.onNodeWithText("上报间隔不合法").assertIsDisplayed()
                assertEquals(interval, context.reportDuration)
                compose.onNode(hasSetTextAction()).performTextReplacement("1000")
                compose.onNodeWithText("保存").performClick()
                assertEquals(1000, context.reportDuration)
                compose.scrollToText("位置上报间隔").performClick()
                compose.onNode(hasSetTextAction()).performTextReplacement("999")
                compose.onNodeWithText("取消").performClick()
                assertEquals(1000, context.reportDuration)
            }
        } finally { context.accuracy = accuracy; context.reportDuration = interval }
    }

    @Test fun speedAndSatelliteEditorsKeepExistingWritePaths() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val speed = context.speed
        val satellites = context.minSatelliteCount
        val runtime = com.system.location.service.runtime.ScenarioRuntime
        val backend = runtime.state.value.backend
        org.junit.Assert.assertFalse("This test only edits idle configuration", runtime.state.value.isActive)
        acceptSelectedMapForUiTest()
        grantUiPermissions()
        try {
            // Selection only; no backend preparation, scene start or Native hooks.
            org.junit.Assert.assertTrue(kotlinx.coroutines.runBlocking {
                runtime.selectBackend(com.system.location.service.core.backend.BackendType.XPOSED).await()
            })
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.openPage(scenario, R.id.nav_settings_motion)
                compose.scrollToText("摇杆速度").performClick()
                compose.onNode(hasSetTextAction()).performTextReplacement("NaN")
                compose.onNodeWithText("保存").performClick()
                compose.onNodeWithText("速度需在 0–1000 米/秒之间").assertIsDisplayed()
                compose.onNode(hasSetTextAction()).performTextReplacement("3.5")
                compose.onNodeWithText("保存").performClick()
                compose.waitUntil(5_000) { context.speed == 3.5 }
                compose.openPage(scenario, R.id.nav_settings_gnss)
                compose.scrollToText("最少卫星数").performClick()
                compose.onNode(hasSetTextAction()).performTextReplacement("129")
                compose.onNodeWithText("保存").performClick()
                compose.onNodeWithText("卫星数量需在 0–128 之间；0 表示自动生成").assertIsDisplayed()
                assertEquals(satellites, context.minSatelliteCount)
                compose.onNode(hasSetTextAction()).performTextReplacement("0")
                scenario.recreate()
                compose.onNode(hasSetTextAction()).assertTextContains("0")
                captureUi("number-setting-satellites")
                compose.onNodeWithText("保存").performClick()
                assertEquals(0, context.minSatelliteCount)
                compose.scrollToText("最少卫星数").performClick()
                compose.onNode(hasSetTextAction()).performTextReplacement("20")
                compose.onNodeWithText("取消").performClick()
                assertEquals(0, context.minSatelliteCount)
            }
        } finally {
            context.speed = speed
            context.minSatelliteCount = satellites
            org.junit.Assert.assertTrue(kotlinx.coroutines.runBlocking { runtime.selectBackend(backend).await() })
        }
    }
}
