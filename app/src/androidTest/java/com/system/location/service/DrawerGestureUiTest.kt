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

    @Test fun edgeSwipeBackSelectionAndRecreationKeepNavigationState() {
        acceptSelectedMapForUiTest()
        grantUiPermissions()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithContentDescription("打开菜单").assertIsDisplayed()
            fun drawer(open: Boolean) {
                compose.waitForIdle()
                compose.waitUntil(5_000) {
                    var matches = false
                    scenario.onActivity {
                        matches = if (open) it.binding.drawerLayout.isDrawerOpen(GravityCompat.START)
                            else !it.binding.drawerLayout.isDrawerVisible(GravityCompat.START)
                    }
                    matches
                }
            }
            onView(withId(R.id.drawer_layout)).perform(GeneralSwipeAction(
                Swipe.SLOW,
                CoordinatesProvider { view ->
                    val xy = IntArray(2).also { view.getLocationOnScreen(it) }
                    floatArrayOf(xy[0] + 4 * view.resources.displayMetrics.density, xy[1] + view.height * .4f)
                },
                CoordinatesProvider { view ->
                    val xy = IntArray(2).also { view.getLocationOnScreen(it) }
                    floatArrayOf(xy[0] + view.width * .75f, xy[1] + view.height * .4f)
                }, Press.FINGER))
            drawer(true)
            captureUi("drawer-home-selection")
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            drawer(false)
            compose.onNodeWithText("开始模拟").assertIsDisplayed()

            compose.onNodeWithContentDescription("打开菜单").performClick()
            drawer(true)
            onView(withText("设置")).perform(click())
            drawer(false)
            compose.onNodeWithText("地图与定位").performClick()
            compose.onNodeWithText("定位精度").assertIsDisplayed()
            scenario.recreate()
            compose.onNodeWithText("定位精度").assertIsDisplayed()
            scenario.onActivity {
                assertTrue(it.binding.navView.menu.findItem(R.id.nav_settings).isChecked)
                it.onSupportNavigateUp()
            }
            compose.onNodeWithText("地图与定位").assertIsDisplayed()
            scenario.onActivity { it.binding.drawerLayout.openDrawer(GravityCompat.START) }
            drawer(true)
            scenario.recreate()
            drawer(true)
            captureUi("drawer-settings-selection-restored")
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            drawer(false)
            scenario.onActivity {
                assertEquals(R.id.nav_settings, it.findNavController(R.id.nav_host_fragment_content_main).currentDestination?.id)
            }
        }
    }
}
