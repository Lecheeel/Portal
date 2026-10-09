package com.system.location.service

import android.Manifest
import androidx.navigation.findNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.core.gpx.GpxCodec
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.scenario.Route
import com.system.location.service.ext.*
import com.system.location.service.runtime.ScenarioRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SimulationFeaturesTest {
    @Test fun androidSaxPreservesSeparatedTracksAndRejectsExternalEntities() {
        val route = Route("r", "测试 & 路线", listOf(Wgs84(1.0, 2.0), Wgs84(1.1, 2.1)))
        val xml = GpxCodec.encode(listOf(route, route.copy(id = "b", name = "第二段")))
        assertEquals(listOf(route.points, route.points), GpxCodec.decode(xml).map { it.points })
        assertThrows(Exception::class.java) { GpxCodec.decode("<!DOCTYPE gpx [<!ENTITY x SYSTEM 'file:///etc/hosts'>]><gpx>&x;</gpx>") }
    }
    @Test fun settingsExposeMotionChannelsAndBackgroundGuidanceAndRuntimeExport() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS).forEach { instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it) }
        val old = context.gmsMockEnabled
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.findNavController(R.id.nav_host_fragment_content_main).navigate(R.id.nav_settings) }
                onView(withId(R.id.gms_mock_switch)).perform(scrollTo(), click())
                assertEquals(!old, context.gmsMockEnabled)
                onView(withId(R.id.platform_fused_switch)).perform(scrollTo()).check(matches(isDisplayed()))
                onView(withId(R.id.startup_burst_switch)).perform(scrollTo()).check(matches(isDisplayed()))
                onView(withId(R.id.smooth_route_switch)).perform(scrollTo()).check(matches(isDisplayed()))
                onView(withId(R.id.orbit_period_button)).perform(scrollTo()).check(matches(isDisplayed()))
                onView(withId(R.id.background_guidance)).perform(scrollTo(), click())
                onView(withText(R.string.background_guidance)).check(matches(isDisplayed()))
                onView(withText(android.R.string.cancel)).perform(click())
                scenario.onActivity { it.findNavController(R.id.nav_host_fragment_content_main).navigate(R.id.nav_runtime) }
                onView(withId(R.id.toggle_logs)).perform(scrollTo(), click())
                onView(withId(R.id.export_logs)).perform(scrollTo()).check(matches(isDisplayed()))
                val report = runBlocking { ScenarioRuntime.diagnosticReport() }
                assertTrue(report.contains(BuildConfig.VERSION_NAME))
                assertTrue(report.contains("Android"))
            }
        } finally { context.gmsMockEnabled = old }
    }
}
