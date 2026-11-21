package com.system.location.service

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.core.view.GravityCompat
import androidx.navigation.findNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.CoordinatesProvider
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DrawerGestureUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun mapPansKeepDrawerClosedAndMenuNavigationSurvivesRecreation() {
        acceptSelectedMapForUiTest()
        grantUiPermissions()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithContentDescription("打开菜单").assertIsDisplayed()
            fun drawer(open: Boolean) {
                compose.waitForIdle()
                compose.waitUntil(5_000) {
                    var matches = false
                    scenario.onActivity {
                        matches = if (open) it.drawerState.isOpen
                            else !(it.drawerState.isOpen || it.drawerState.isAnimationRunning)
                    }
                    matches
                }
            }
            // Closed navigation must not steal map drags, including diagonal and edge starts.
            listOf(.35f to .75f, .75f to .25f, .01f to .65f).forEach { (start, end) ->
                compose.onNodeWithTag("portal_shell").performTouchInput {
                    swipe(start = androidx.compose.ui.geometry.Offset(width * start, centerY),
                        end = androidx.compose.ui.geometry.Offset(width * end, centerY + height * .05f), durationMillis = 600)
                }
                drawer(false)
                scenario.onActivity { assertEquals(androidx.compose.material3.DrawerValue.Closed, it.drawerState.targetValue) }
            }
            compose.onNodeWithContentDescription("打开菜单").performClick()
            drawer(true)
            captureUi("drawer-home-selection")
            scenario.onActivity { it.systemBackForUiTest() }
            drawer(false)
            compose.onNodeWithText("开始模拟").assertIsDisplayed()

            compose.onNodeWithContentDescription("打开菜单").performClick()
            drawer(true)
            compose.onNodeWithText("设置").performClick()
            drawer(false)
            compose.waitUntil(5_000) {
                runCatching { compose.onNodeWithText("地图与定位").assertIsDisplayed(); true }.getOrDefault(false)
            }
            compose.onNodeWithText("地图与定位").performClick()
            compose.waitUntil(5_000) {
                runCatching { compose.onNodeWithText("定位精度").assertIsDisplayed(); true }.getOrDefault(false)
            }
            compose.onNodeWithText("定位精度").assertIsDisplayed()
            scenario.recreate()
            compose.onNodeWithText("定位精度").assertIsDisplayed()
            scenario.onActivity {
                assertTrue((it.selectedDrawerId == R.id.nav_settings))
                it.onSupportNavigateUp()
            }
            compose.waitUntil(5_000) {
                runCatching { compose.onNodeWithText("地图与定位").assertIsDisplayed(); true }.getOrDefault(false)
            }
            compose.onNodeWithText("地图与定位").assertIsDisplayed()
            compose.onNodeWithTag("portal_shell").performTouchInput {
                swipe(start = androidx.compose.ui.geometry.Offset(4f, centerY),
                    end = androidx.compose.ui.geometry.Offset(width * .75f, centerY), durationMillis = 600)
            }
            drawer(false)
            scenario.onActivity { assertEquals(androidx.compose.material3.DrawerValue.Closed, it.drawerState.targetValue) }
            scenario.onActivity { it.openDrawer() }
            drawer(true)
            scenario.recreate()
            drawer(true)
            captureUi("drawer-settings-selection-restored")
            scenario.onActivity { it.systemBackForUiTest() }
            drawer(false)
            scenario.onActivity {
                assertEquals(R.id.nav_settings, it.findNavController(R.id.nav_host_fragment_content_main).currentDestination?.id)
            }
        }
    }
}
