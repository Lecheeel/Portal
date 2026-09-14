package com.system.location.service

import android.Manifest
import android.graphics.Bitmap
import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.findNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.model.LatLng
import com.system.location.service.core.planning.DrawingMode
import com.system.location.service.runtime.ScenarioRuntime
import com.system.location.service.ui.viewmodel.AMapViewModel
import org.hamcrest.Matchers.not
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MapUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @Before fun permissions() {
        listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, it)
        }
    }

    @Test fun homeHidesCrosshairAndRequiresExplicitLocationConfirmation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // The old center cursor id is intentionally removed from the layout.
            assertEquals(0, instrumentation.targetContext.resources.getIdentifier("ivCrosshair", "id", instrumentation.targetContext.packageName))
            onView(withId(R.id.btn_apply_location)).check(matches(not(isEnabled())))
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[AMapViewModel::class.java]
                assertNull(model.markedLoc)
                assertFalse(ScenarioRuntime.state.value.isActive)
                model.rememberOriginalLocation(39.9 to 116.4, isMock = false, simulationActive = false)
            }
            scenario.recreate()
            onView(withId(R.id.original_position)).check(matches(withText("● 原始位置已保留 · 点击查看")))
            onView(withId(R.id.btn_apply_location)).check(matches(not(isEnabled())))
            capture("home")
        }
    }

    @Test fun runtimeStartsCollapsedAndSectionsSurviveRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.btn_apply_location)).check(matches(isDisplayed()))
            scenario.onActivity { it.findNavController(R.id.nav_host_fragment_content_main).navigate(R.id.nav_runtime) }
            onView(withId(R.id.state_details)).check(matches(withEffectiveVisibility(Visibility.GONE)))
            onView(withId(R.id.backend_details)).check(matches(withEffectiveVisibility(Visibility.GONE)))
            onView(withId(R.id.log_details)).check(matches(withEffectiveVisibility(Visibility.GONE)))
            capture("runtime-overview")
            onView(withId(R.id.toggle_logs)).perform(scrollTo(), click())
            onView(withId(R.id.log_filter)).check(matches(isDisplayed()))
            onView(withId(R.id.clear_logs)).perform(scrollTo(), click())
            onView(withId(R.id.diagnostics)).check(matches(withText("暂无符合条件的日志")))
            scenario.recreate()
            onView(withId(R.id.log_details)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
            onView(withId(R.id.toggle_logs)).perform(scrollTo())
            capture("runtime-logs")
        }
    }

    @Test fun manualModesRenderAndFreehandCanBeUndone() {
        File(instrumentation.targetContext.filesDir, "route_draft.json").delete()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.findNavController(R.id.nav_host_fragment_content_main).navigate(R.id.nav_route_edit) }
            onView(withId(R.id.mode_freehand)).perform(click())
            onView(withId(R.id.gesture_hint)).check(matches(withText(org.hamcrest.Matchers.containsString("单指画线"))))
            onView(withId(R.id.confirm)).check(matches(not(isEnabled())))
            onView(withId(R.id.undo)).check(matches(not(isEnabled())))
            onView(withId(R.id.mode_points)).perform(click())
            onView(withId(R.id.gesture_hint)).check(matches(withText(org.hamcrest.Matchers.containsString("依次点击地图加点"))))
            capture("route-points")
        }
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 10_000
        while (!predicate()) {
            check(android.os.SystemClock.uptimeMillis() < deadline) { "UI state did not settle" }
            Thread.sleep(100)
        }
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-captures").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
