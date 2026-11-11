package com.system.location.service

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.ext.sharedPrefs
import com.system.location.service.maps.MapProvider
import com.system.location.service.maps.PortalMapView
import com.system.location.service.maps.mapProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeMapLifetimeUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun pausedHomeMapIsReusedButReleasedOnMemoryPressureAndActivityRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.sharedPrefs
        val hadProvider = preferences.contains("map_provider")
        val originalProvider = preferences.getString("map_provider", null)
        try {
            for (provider in MapProvider.entries) {
                context.mapProvider = provider
                acceptSelectedMapForUiTest()
                grantUiPermissions()
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    fun drawerPage(destination: Int) {
                        compose.waitForIdle()
                        scenario.onActivity { it.binding.navView.menu.performIdentifierAction(destination, 0) }
                        compose.waitForIdle()
                    }
                    fun currentMap(): PortalMapView {
                        compose.waitUntil(5_000) {
                            runCatching { compose.onNodeWithText("开始模拟").assertIsDisplayed(); true }.getOrDefault(false)
                        }
                        compose.onNodeWithText("开始模拟").assertIsDisplayed()
                        lateinit var map: PortalMapView
                        scenario.onActivity { activity ->
                            map = activity.window.decorView.descendantsOfType<PortalMapView>().single { it.isAttachedToWindow }
                        }
                        assertEquals(provider, map.provider)
                        assertTrue(map.childCount > 0)
                        return map
                    }
                    val initial = currentMap()
                    lateinit var home: androidx.fragment.app.Fragment
                    scenario.onActivity { activity ->
                        home = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main)!!
                            .childFragmentManager.primaryNavigationFragment!!
                    }
                    repeat(2) {
                        drawerPage(R.id.nav_settings)
                        compose.onNodeWithText("地图与定位").assertIsDisplayed()
                        assertNull("Paused map must detach from its previous AndroidView holder", initial.parent)
                        drawerPage(R.id.nav_home)
                        assertSame("Returning must not initialize the SDK again", initial, currentMap())
                    }
                    drawerPage(R.id.nav_settings)
                    scenario.onActivity { home.onLowMemory() }
                    assertEquals("Memory pressure must release the offscreen SDK", 0, initial.childCount)
                    drawerPage(R.id.nav_home)
                    val replacement = currentMap()
                    assertNotSame(initial, replacement)
                    scenario.recreate()
                    assertEquals("Activity destruction must release the cached SDK", 0, replacement.childCount)
                    assertNotSame(replacement, currentMap())
                    captureUi("home-map-reused-${provider.name.lowercase()}")
                }
            }
        } finally {
            preferences.edit().apply {
                if (hadProvider) putString("map_provider", originalProvider) else remove("map_provider")
            }.commit()
        }
    }
}
