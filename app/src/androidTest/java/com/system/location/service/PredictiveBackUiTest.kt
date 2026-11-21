package com.system.location.service

import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
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
    private fun event(progress: Float) = NavigationEvent(swipeEdge = NavigationEvent.EDGE_LEFT,
        progress = progress, touchX = 200f * progress, touchY = 400f)

    @Test fun drawerTracksProgressCancelsAndCommitsWithoutLeavingThePage() {
        acceptSelectedMapForUiTest()
        grantUiPermissions()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val input = DirectNavigationEventInput()
            scenario.onActivity { it.navigationEventDispatcher.addInput(input) }
            compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("开始模拟").assertIsDisplayed(); true }.getOrDefault(false) }
            scenario.onActivity { it.openDrawer() }
            compose.waitUntil(5_000) {
                var open = false
                scenario.onActivity { open = it.drawerState.isOpen && !it.drawerState.isAnimationRunning }
                open
            }
            scenario.onActivity {
                input.backStarted(event(0f))
                input.backProgressed(event(.65f))
                // The Compose drawer handles progress without navigating the underlying page.
                assertEquals(R.id.nav_home, it.currentDestinationId)
                assertTrue(it.drawerState.isOpen)
                input.backCancelled()
            }
            compose.waitUntil(5_000) {
                var restored = false
                scenario.onActivity { restored = it.drawerState.isOpen && !it.drawerState.isAnimationRunning }
                restored
            }
            scenario.onActivity {
                assertTrue(it.drawerState.isOpen)
                input.backStarted(event(0f))
                input.backProgressed(event(.8f))
                input.backCompleted()
            }
            compose.waitUntil(5_000) {
                var closed = false
                scenario.onActivity { closed = !(it.drawerState.isOpen || it.drawerState.isAnimationRunning) }
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
            val input = DirectNavigationEventInput()
            scenario.onActivity { it.navigationEventDispatcher.addInput(input) }
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
                input.backStarted(event(0f))
            }
            compose.waitForIdle()
            scenario.onActivity {
                input.backProgressed(event(.5f))
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
            scenario.onActivity { input.backCancelled() }
            resumed()
            compose.onNodeWithText("定位精度").assertIsDisplayed()
            scenario.onActivity {
                assertEquals(R.id.nav_settings_location, it.findNavController(R.id.nav_host_fragment_content_main).currentDestination?.id)
                input.backStarted(event(0f))
            }
            compose.waitForIdle()
            scenario.onActivity {
                input.backProgressed(event(.8f))
                input.backCompleted()
            }
            resumed()
            compose.onNodeWithText("常用设置").assertIsDisplayed()
            scenario.onActivity { assertEquals(R.id.nav_settings, it.findNavController(R.id.nav_host_fragment_content_main).currentDestination?.id) }
        }
    }
}
