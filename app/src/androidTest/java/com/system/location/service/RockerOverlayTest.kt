package com.system.location.service

import android.Manifest
import android.content.Context
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.navigation.findNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.matcher.RootMatchers.withDecorView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.runtime.ScenarioRuntime
import com.system.location.service.ui.mock.RockerOverlay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Integration test for overlay ownership and the user's persisted visibility choice. */
@RunWith(AndroidJUnit4::class)
class RockerOverlayTest {
    @Test fun overlaySurvivesNavigationRecreationAndStopUntilHomeDisablesIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val overlayWasAllowed = Settings.canDrawOverlays(context)
        fun overlayPermission(mode: String) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "appops set ${context.packageName} SYSTEM_ALERT_WINDOW $mode")).use { it.readBytes() }
        }
        overlayPermission("allow")
        fun overlay(id: Int) = onView(withId(id)).inRoot(
            withDecorView(hasDescendant(withId(R.id.rocker_minimized))))
        listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
        }
        val preferences = context.getSharedPreferences("rocker_overlay", Context.MODE_PRIVATE)
        val previouslyEnabled = preferences.getBoolean("enabled", true)
        preferences.edit().remove("enabled").commit()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                onView(withId(R.id.btn_apply_location)).check(matches(isDisplayed()))
                scenario.onActivity {
                    assertTrue(RockerOverlay.isEnabled)
                    assertTrue("Grant overlay permission on the test device", RockerOverlay.isVisible)
                }
                overlay(R.id.rocker_minimized).check(matches(isDisplayed())).perform(click())
                overlay(R.id.rocker).check(matches(isDisplayed()))
                overlay(R.id.expand_menu).perform(click())
                overlay(R.id.rocker_minimized).check(matches(isDisplayed()))
                scenario.onActivity { it.findNavController(R.id.nav_host_fragment_content_main).navigate(R.id.nav_runtime) }
                scenario.recreate()
                overlay(R.id.rocker_minimized).check(matches(isDisplayed()))
                // Stopping even an idle scenario must not dismiss the independent overlay.
                runBlocking { ScenarioRuntime.stop().await() }
                overlay(R.id.rocker_minimized).check(matches(isDisplayed()))
                scenario.onActivity { it.findNavController(R.id.nav_host_fragment_content_main).navigate(R.id.nav_home) }
                onView(withId(R.id.btnQuickRocker)).perform(click())
                scenario.onActivity {
                    assertFalse(RockerOverlay.isEnabled)
                    assertFalse(RockerOverlay.isVisible)
                }
                scenario.recreate()
                scenario.onActivity { assertFalse(RockerOverlay.isVisible) }
                onView(withId(R.id.btnQuickRocker)).perform(click())
                overlay(R.id.rocker_minimized).check(matches(isDisplayed()))
            }
        } finally {
            instrumentation.runOnMainSync { RockerOverlay.setEnabled(false) }
            preferences.edit().putBoolean("enabled", previouslyEnabled).commit()
            if (!overlayWasAllowed) overlayPermission("default")
        }
    }
}
