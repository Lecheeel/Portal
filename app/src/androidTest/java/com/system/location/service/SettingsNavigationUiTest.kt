package com.system.location.service

import android.view.Menu
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
class SettingsNavigationUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun directoryOpensEachCategoryAndPreservesDetailAcrossRecreation() {
        acceptSelectedMapForUiTest()
        grantUiPermissions()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.openPage(scenario, R.id.nav_settings)
            compose.onNodeWithText("定位海拔").assertDoesNotExist()
            captureUi("settings-directory-green")
            val categories = listOf(
                Triple("地图与定位", "定位海拔", R.id.nav_settings_location),
                Triple("运动与摇杆", "路线平滑运动", R.id.nav_settings_motion),
                Triple("卫星与 GNSS", "最少卫星数", R.id.nav_settings_gnss),
                Triple("分应用规则", "分应用路由（实验）", R.id.nav_settings_apps),
                Triple("系统与环境模拟", "SIM 就绪", R.id.nav_settings_system),
                Triple("高级与实验", "启动突发", R.id.nav_settings_advanced),
                Triple("后台与隐私", "后台运行", R.id.nav_settings_privacy),
                Triple("关于与更新", "检查更新", R.id.nav_settings_updates),
            )
            categories.forEach { (title, marker, id) ->
                compose.scrollToText(title).performClick()
                compose.scrollToText(marker).assertIsDisplayed()
                scenario.onActivity { assertEquals(id, it.findNavController(R.id.nav_host_fragment_content_main).currentDestination?.id) }
                scenario.recreate()
                compose.scrollToText(marker).assertIsDisplayed()
                if (id == R.id.nav_settings_location) captureUi("settings-location-green")
                scenario.onActivity { it.onSupportNavigateUp() }
                compose.scrollToText(title).assertIsDisplayed()
                scenario.onActivity { assertEquals(R.id.nav_settings, it.findNavController(R.id.nav_host_fragment_content_main).currentDestination?.id) }
            }
        }
    }

    @Test fun groupedDrawerKeepsOnlyOneDestinationSelected() {
        acceptSelectedMapForUiTest()
        grantUiPermissions()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            listOf(R.id.nav_settings, R.id.nav_mock, R.id.nav_runtime, R.id.nav_home, R.id.nav_route_gallery).forEach { id ->
                scenario.onActivity { it.navigateFromDrawer(id) }
                compose.waitForIdle()
                scenario.onActivity {
                    assertEquals(id, it.findNavController(R.id.nav_host_fragment_content_main).currentDestination?.id)
                    assertEquals(id, it.selectedDrawerId)
                }
            }
            scenario.recreate()
            scenario.onActivity { it.openDrawer() }
            compose.waitForIdle()
            captureUi("drawer-green")
            scenario.onActivity { it.systemBackForUiTest() }
            compose.waitUntil(5_000) {
                var closed = false
                scenario.onActivity { closed = !it.drawerState.isOpen }
                closed
            }
            scenario.onActivity { assertFalse(it.drawerState.isOpen) }
        }
    }
}
