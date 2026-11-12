package com.system.location.service

import androidx.activity.BackEventCompat
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.core.view.GravityCompat
import androidx.navigation.findNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PredictiveBackUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun event(progress: Float) = BackEventCompat(200f * progress, 400f, progress, BackEventCompat.EDGE_LEFT)

    @Test fun drawerTracksProgressCancelsAndCommitsWithoutLeavingThePage() {
        acceptSelectedMapForUiTest()
        grantUiPermissions()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("开始模拟").assertIsDisplayed(); true }.getOrDefault(false) }
            scenario.onActivity {
                it.binding.drawerLayout.openDrawer(GravityCompat.START, false)
                it.onBackPressedDispatcher.dispatchOnBackStarted(event(0f))
                it.onBackPressedDispatcher.dispatchOnBackProgressed(event(.65f))
                // Material stretches horizontally from the opening edge and shrinks vertically.
                assertTrue("Drawer must preview the Back gesture", it.binding.navView.scaleY < 1f)
                assertTrue(it.binding.drawerLayout.isDrawerOpen(GravityCompat.START))
                it.onBackPressedDispatcher.dispatchOnBackCancelled()
            }
            compose.waitUntil(5_000) {
                var restored = false
                scenario.onActivity { restored = kotlin.math.abs(it.binding.navView.scaleX - 1f) < .001f &&
                    kotlin.math.abs(it.binding.navView.scaleY - 1f) < .001f }
                restored
            }
            scenario.onActivity {
                assertTrue(it.binding.drawerLayout.isDrawerOpen(GravityCompat.START))
                it.onBackPressedDispatcher.dispatchOnBackStarted(event(0f))
                it.onBackPressedDispatcher.dispatchOnBackProgressed(event(.8f))
                it.onBackPressedDispatcher.onBackPressed()
            }
            compose.waitUntil(5_000) {
                var closed = false
                scenario.onActivity { closed = !it.binding.drawerLayout.isDrawerVisible(GravityCompat.START) }
                closed
            }
            compose.onNodeWithText("开始模拟").assertIsDisplayed()
            scenario.onActivity { assertEquals(R.id.nav_home, it.findNavController(R.id.nav_host_fragment_content_main).currentDestination?.id) }
        }
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 34)
    @Test fun settingsBackCancellationKeepsDetailAndCommitReturnsToDirectory() {
        acceptSelectedMapForUiTest()
        grantUiPermissions()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.openPage(scenario, R.id.nav_settings)
            compose.onNodeWithText("地图与定位").performClick()
            fun resumed() = compose.waitUntil(5_000) {
                var ready = false
                scenario.onActivity {
                    ready = it.supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main)!!
                        .childFragmentManager.primaryNavigationFragment?.lifecycle?.currentState == androidx.lifecycle.Lifecycle.State.RESUMED
                }
                ready
            }
            resumed()
            compose.onNodeWithText("定位精度").assertIsDisplayed()
            scenario.onActivity {
                val page = it.supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main)!!
                    .childFragmentManager.primaryNavigationFragment!!
                assertTrue((page.returnTransition as androidx.transition.Slide).isSeekingSupported)
                it.onBackPressedDispatcher.dispatchOnBackStarted(event(0f))
            }
            compose.waitForIdle()
            scenario.onActivity {
                it.onBackPressedDispatcher.dispatchOnBackProgressed(event(.5f))
            }
            // A seekable Back must attach the previous page before the gesture commits.
            compose.waitUntil(5_000) {
                var previewing = false
                scenario.onActivity {
                    val host = it.supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main)!!
                    previewing = host.childFragmentManager.fragments.count { page ->
                        page.view?.parent != null && page.view?.visibility == android.view.View.VISIBLE
                    } >= 2
                }
                previewing
            }
            scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackCancelled() }
            resumed()
            compose.onNodeWithText("定位精度").assertIsDisplayed()
            scenario.onActivity {
                assertEquals(R.id.nav_settings_location, it.findNavController(R.id.nav_host_fragment_content_main).currentDestination?.id)
                it.onBackPressedDispatcher.dispatchOnBackStarted(event(0f))
            }
            compose.waitForIdle()
            scenario.onActivity {
                it.onBackPressedDispatcher.dispatchOnBackProgressed(event(.8f))
                it.onBackPressedDispatcher.onBackPressed()
            }
            resumed()
            compose.onNodeWithText("常用设置").assertIsDisplayed()
            scenario.onActivity { assertEquals(R.id.nav_settings, it.findNavController(R.id.nav_host_fragment_content_main).currentDestination?.id) }
        }
    }
}
