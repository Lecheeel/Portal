package com.system.location.service

import android.widget.EditText
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.*
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
        com.system.location.service.privacy.SdkPrivacy.acceptMaps()
        grantUiPermissions()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.openPage(scenario, R.id.nav_settings)
                compose.scrollToText("定位海拔").performClick()
                val editor = onView(isAssignableFrom(EditText::class.java)).inRoot(isDialog())
                editor.perform(replaceText("10001"))
                onView(withText("保存")).inRoot(isDialog()).perform(click())
                editor.check(matches(isDisplayed()))
                assertEquals(original, context.altitude, 0.001)
                editor.perform(replaceText("1234.5"))
                onView(withText("保存")).inRoot(isDialog()).perform(click())
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
}
