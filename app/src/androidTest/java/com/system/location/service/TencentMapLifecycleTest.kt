package com.system.location.service

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import org.junit.Rule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.maps.*
import com.system.location.service.privacy.TencentPrivacy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TencentMapLifecycleTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun unconsentedViewDoesNotConstructVendorMap() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val original = context.mapProvider
        val prefs = context.getSharedPreferences("sdk_privacy", 0)
        val accepted = prefs.getBoolean("tencent_maps_v1", false)
        try {
            context.mapProvider = MapProvider.TENCENT
            check(prefs.edit().remove("tencent_maps_v1").commit())
            instrumentation.runOnMainSync {
                val view = PortalMapView(context)
                assertEquals(0, view.childCount)
                assertTrue(runCatching { view.onCreate(null) }.isFailure)
                assertEquals(0, view.childCount)
                view.onDestroy(); view.onDestroy()
            }
        } finally {
            context.mapProvider = original
            prefs.edit().putBoolean("tencent_maps_v1", accepted).commit()
        }
    }

    @Test fun acceptedTencentMapSurvivesActivityRecreationAndRouteEditor() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val original = context.mapProvider
        listOf(android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.POST_NOTIFICATIONS).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
        }
        try {
            instrumentation.runOnMainSync { TencentPrivacy.accept(context); context.mapProvider = MapProvider.TENCENT }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitForIdle()
                scenario.onActivity {
                    val map = it.window.decorView.descendantsOfType<PortalMapView>().filter { view -> view.isShown }.single()
                    assertEquals(MapProvider.TENCENT, map.provider)
                    assertEquals(1, map.childCount)
                }
                scenario.recreate()
                compose.waitForIdle()
                scenario.onActivity {
                    assertEquals(MapProvider.TENCENT, it.window.decorView.descendantsOfType<PortalMapView>().filter { view -> view.isShown }.single().provider)
                    androidx.navigation.Navigation.findNavController(it, R.id.nav_host_fragment_content_main).navigate(R.id.nav_route_edit)
                }
                compose.waitForIdle()
                scenario.onActivity {
                    val map = it.window.decorView.descendantsOfType<PortalMapView>().filter { view -> view.isShown }.single()
                    assertEquals(MapProvider.TENCENT, map.provider)
                    assertEquals(1, map.childCount)
                }
                scenario.recreate()
                compose.waitForIdle()
                scenario.onActivity { assertEquals(MapProvider.TENCENT, it.window.decorView.descendantsOfType<PortalMapView>().filter { view -> view.isShown }.single().provider) }
            }
        } finally { context.mapProvider = original }
    }
}
