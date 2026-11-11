package com.system.location.service

import android.graphics.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.maps.PortalMapView
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run on a compact viewport, including the device's actual native map and dialog windows. */
@RunWith(AndroidJUnit4::class)
class CompactLayoutUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun numericSettingsAndRouteMapRemainReachableWithLargeTextOnSmallScreen() {
        val config = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration
        assumeTrue(config.screenWidthDp in 280..360 && config.screenHeightDp <= 640 && config.fontScale >= 1.29f)
        acceptSelectedMapForUiTest()
        grantUiPermissions()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.openPage(scenario, R.id.nav_settings)
            compose.scrollToText("关于与更新").assertIsDisplayed()
            compose.scrollToText("地图与定位").performClick()
            compose.scrollToText("定位精度").performClick()
            compose.onNodeWithText("设置精度").assertIsDisplayed()
            compose.onNodeWithText("保存").assertIsDisplayed()
            compose.onNodeWithText("取消").performClick()
            compose.openPage(scenario, R.id.nav_route_edit)
            val save = compose.onNodeWithText("保存路线")
            // A normal layout may already fit; compact panels can scroll to the same action.
            runCatching { save.performScrollTo() }
            save.assertIsDisplayed()
            captureUi("route-editor-compact-large-text")
            scenario.onActivity { activity ->
                val map = activity.window.decorView.descendantsOfType<PortalMapView>().single { it.isAttachedToWindow }
                val visible = Rect()
                assertTrue("Map must remain visible", map.getGlobalVisibleRect(visible))
                assertTrue("Map must retain a usable 100dp viewport", visible.height() >= 100 * activity.resources.displayMetrics.density)
            }
        }
    }
}
