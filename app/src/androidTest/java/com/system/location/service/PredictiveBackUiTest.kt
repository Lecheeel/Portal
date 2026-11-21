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
            try {
                compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("开始模拟").assertIsDisplayed(); true }.getOrDefault(false) }
                scenario.onActivity { it.openDrawer() }
                compose.waitUntil(5_000) {
                    var open = false
                    scenario.onActivity { open = it.drawerState.isOpen && !it.drawerState.isAnimationRunning }
                    open
                }
                compose.waitForIdle()
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
                compose.waitForIdle()
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
            } finally {
                if (scenario.state != androidx.lifecycle.Lifecycle.State.DESTROYED) scenario.onActivity {
                    try { input.backCancelled() } finally { it.navigationEventDispatcher.removeInput(input) }
                }
            }
        }
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 34)
    @Test fun settingsBackCancellationKeepsDetailAndCommitReturnsToDirectory() {
        acceptSelectedMapForUiTest()
        grantUiPermissions()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val input = DirectNavigationEventInput()
            scenario.onActivity { it.navigationEventDispatcher.addInput(input) }
            try {
                compose.openPage(scenario, R.id.nav_settings)
                lateinit var directory: androidx.fragment.app.Fragment
                lateinit var detail: android.view.View
                scenario.onActivity {
                    directory = it.supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main)!!
                        .childFragmentManager.primaryNavigationFragment!!
                }
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
                compose.waitForIdle()
                compose.onNodeWithText("定位精度").assertIsDisplayed()
                val detailTitle = hasText("地图与定位") and hasAnyAncestor(hasTestTag("portal_toolbar"))
                val titleNode = compose.onNode(detailTitle).fetchSemanticsNode()
                val titleBeforeBack = titleNode.boundsInWindow
                scenario.onActivity {
                    val page = it.supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main)!!
                        .childFragmentManager.primaryNavigationFragment!!
                    assertTrue((page.returnTransition as androidx.transition.Slide).isSeekingSupported)
                    detail = page.requireView()
                    input.backStarted(event(0f))
                }
                compose.waitForIdle()
                scenario.onActivity {
                    input.backProgressed(event(.5f))
                }
                // A disappearing page may move into the transition overlay, outside both the
                // active fragment list and ordinary container children. Check both actual views.
                compose.waitUntil(5_000) {
                    var previewing = false
                    scenario.onActivity {
                        val previous = directory.view
                        previewing = previous?.isAttachedToWindow == true && previous.width > 0 &&
                            previous.height > 0 && detail.isAttachedToWindow
                    }
                    previewing
                }
                captureUi("settings-back-title-preview")
                // The outgoing Compose root temporarily leaves the test registry when its
                // Fragment view enters the transition overlay. Follow the actual title node.
                compose.waitUntil(5_000) { titleNode.boundsInWindow.left > titleBeforeBack.left + 1f }
                scenario.onActivity { input.backCancelled() }
                compose.waitUntil(5_000) {
                    var settled = false
                    scenario.onActivity {
                        settled = directory.view?.isAttachedToWindow != true && detail.isAttachedToWindow
                    }
                    settled
                }
                resumed()
                compose.waitForIdle()
                compose.onNodeWithText("定位精度").assertIsDisplayed()
                val cancelledTitle = compose.onNode(detailTitle).fetchSemanticsNode().boundsInWindow
                assertEquals("Cancelling Back must restore the title with its page", titleBeforeBack.left, cancelledTitle.left, 1f)
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
            } finally {
                if (scenario.state != androidx.lifecycle.Lifecycle.State.DESTROYED) scenario.onActivity {
                    try { input.backCancelled() } finally { it.navigationEventDispatcher.removeInput(input) }
                }
            }
        }
    }
}
