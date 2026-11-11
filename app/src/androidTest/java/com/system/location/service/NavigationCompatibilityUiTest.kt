package com.system.location.service

import android.os.Bundle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.navigation.NavDeepLinkBuilder
import androidx.navigation.findNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationCompatibilityUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun explicitLinksKeepLegacySatelliteAndSettingsDestinations() {
        acceptSelectedMapForUiTest()
        grantUiPermissions()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cases = listOf(
            Triple(R.id.nav_gnss_mock, "星图信息", R.id.nav_mock),
            Triple(R.id.nav_settings_system, "SIM 就绪", R.id.nav_settings),
            Triple(R.id.nav_route_edit, "保存路线", R.id.nav_route_gallery),
        )
        for ((destination, marker, drawerItem) in cases) {
            val intent = NavDeepLinkBuilder(context)
                .setComponentName(MainActivity::class.java)
                .setGraph(R.navigation.mobile_navigation)
                .setDestination(destination)
                .createTaskStackBuilder().intents.last()
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                if (destination == R.id.nav_settings_system) compose.scrollToText(marker).assertIsDisplayed()
                else compose.onNodeWithText(marker).assertIsDisplayed()
                scenario.recreate()
                compose.waitForIdle()
                scenario.onActivity {
                    assertEquals(destination, it.findNavController(R.id.nav_host_fragment_content_main).currentDestination?.id)
                    assertTrue("Explicit link must select its drawer section", it.binding.navView.menu.findItem(drawerItem).isChecked)
                }
                if (destination == R.id.nav_gnss_mock) compose.onNodeWithText("卫星").assertIsSelected()
            }
        }
    }

    @Test fun settingsChildPagesKeepSettingsSelectedAndReturnToTheirParent() {
        acceptSelectedMapForUiTest()
        grantUiPermissions()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.openPage(scenario, R.id.nav_settings_apps)
            for (destination in listOf(R.id.nav_sensor_packages, R.id.nav_point_selection, R.id.nav_app_selection)) {
                scenario.onActivity {
                    it.findNavController(R.id.nav_host_fragment_content_main).navigate(destination, Bundle().apply {
                        putString("purpose", "excluded")
                        putString("title", "选择应用")
                        putStringArrayList("selected", arrayListOf())
                    })
                }
                compose.waitForIdle()
                scenario.onActivity {
                    assertEquals(destination, it.findNavController(R.id.nav_host_fragment_content_main).currentDestination?.id)
                    assertTrue("Settings child must keep its section selected", it.binding.navView.menu.findItem(R.id.nav_settings).isChecked)
                    it.onSupportNavigateUp()
                }
                compose.scrollToText("分应用路由（实验）").assertIsDisplayed()
            }
        }
    }
}
