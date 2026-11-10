package com.system.location.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.app.Activity
import android.app.AppOpsManager
import android.app.Instrumentation
import android.os.Process
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import org.junit.Rule
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
import com.system.location.service.ext.speed
import com.system.location.service.ext.sharedPrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/** Integration test for overlay ownership and the user's persisted visibility choice. */
@RunWith(AndroidJUnit4::class)
class RockerOverlayTest {
    @get:Rule val compose = createEmptyComposeRule()
    @org.junit.Before fun acceptMapConsentForThisFixture() {
        acceptSelectedMapForUiTest()
    }
    @Test fun deniedOverlayPermissionOpensSettingsEvenWhenVisibilityWasEnabled() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        grantUiPermissions()
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val previousMode = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, Process.myUid(), context.packageName)
        val preferences = context.getSharedPreferences("rocker_overlay", Context.MODE_PRIVATE)
        val previousPreferences = preferences.all
        val requested = AtomicReference<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Settings.ACTION_MANAGE_OVERLAY_PERMISSION) return null
                requested.set(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        fun permission(mode: String) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "appops set ${context.packageName} SYSTEM_ALERT_WINDOW $mode")).use { it.readBytes() }
        }
        instrumentation.addMonitor(monitor)
        try {
            permission("ignore")
            assertFalse(com.system.location.service.android.window.OverlayUtils.hasOverlayPermissions(context))
            preferences.edit().putBoolean("enabled", true).commit()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.onNodeWithContentDescription("打开悬浮窗").performClick()
                compose.waitUntil(5_000) { requested.get() != null }
                assertEquals("package:${context.packageName}", requested.get().dataString)
                scenario.onActivity { assertFalse(RockerOverlay.isVisible) }
                compose.onNodeWithContentDescription("打开悬浮窗").assertIsDisplayed()
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync { RockerOverlay.setEnabled(false) }
            preferences.edit().apply {
                if (previousPreferences.containsKey("enabled")) putBoolean("enabled", previousPreferences["enabled"] as Boolean)
                else remove("enabled")
            }.commit()
            permission(when (previousMode) {
                AppOpsManager.MODE_ALLOWED -> "allow"
                AppOpsManager.MODE_IGNORED -> "ignore"
                AppOpsManager.MODE_ERRORED -> "deny"
                else -> "default"
            })
        }
    }

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
        val hadSpeed = context.sharedPrefs.contains("speed")
        val savedSpeed = context.speed
        context.speed = 3.05
        preferences.edit().remove("enabled").commit()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.onNodeWithText("开始模拟").assertIsDisplayed()
                scenario.onActivity {
                    assertTrue(RockerOverlay.isEnabled)
                    assertTrue("Grant overlay permission on the test device", RockerOverlay.isVisible)
                }
                overlay(R.id.rocker_minimized).check(matches(isDisplayed()))
                captureUi("overlay-minimized")
                overlay(R.id.rocker_minimized).perform(click())
                overlay(R.id.rocker).check(matches(isDisplayed()))
                overlay(R.id.speed_slider).check(matches(withEffectiveVisibility(Visibility.GONE)))
                captureUi("overlay-expanded")
                overlay(R.id.speed_toggle).perform(click())
                overlay(R.id.speed_slider).check(matches(withContentDescription(R.string.rocker_speed)))
                overlay(R.id.speed_slider).check(matches(isDisplayed()))
                captureUi("overlay-speed-expanded")
                overlay(R.id.speed_up).perform(click())
                runBlocking { withTimeout(5000) { while (kotlin.math.abs(context.speed - 3.15) > 0.001) delay(10) } }
                overlay(R.id.speed).check(matches(withText(context.getString(R.string.overlay_speed_number, context.speed))))
                overlay(R.id.speed_down).perform(click())
                runBlocking { withTimeout(5000) { while (kotlin.math.abs(context.speed - 3.05) > 0.001) delay(10) } }
                overlay(R.id.speed_kmh).check(matches(withText(context.getString(R.string.speed_kmh, context.speed * 3.6))))
                overlay(R.id.expand_menu).perform(click())
                overlay(R.id.rocker_minimized).check(matches(isDisplayed()))
                overlay(R.id.rocker_minimized).perform(click())
                overlay(R.id.speed_slider).check(matches(withEffectiveVisibility(Visibility.GONE)))
                overlay(R.id.speed_summary).check(matches(withText(context.getString(R.string.overlay_speed_value, context.speed))))
                overlay(R.id.expand_menu).perform(click())
                scenario.onActivity { it.findNavController(R.id.nav_host_fragment_content_main).navigate(R.id.nav_runtime) }
                scenario.recreate()
                overlay(R.id.rocker_minimized).check(matches(isDisplayed()))
                // Stopping even an idle scenario must not dismiss the independent overlay.
                runBlocking { ScenarioRuntime.stop().await() }
                overlay(R.id.rocker_minimized).check(matches(isDisplayed()))
                scenario.onActivity { it.findNavController(R.id.nav_host_fragment_content_main).navigate(R.id.nav_home) }
                compose.onNodeWithContentDescription("关闭悬浮窗").performClick()
                scenario.onActivity {
                    assertFalse(RockerOverlay.isEnabled)
                    assertFalse(RockerOverlay.isVisible)
                }
                scenario.recreate()
                scenario.onActivity { assertFalse(RockerOverlay.isVisible) }
                compose.onNodeWithContentDescription("打开悬浮窗").performClick()
                overlay(R.id.rocker_minimized).check(matches(isDisplayed()))
            }
        } finally {
            instrumentation.runOnMainSync { RockerOverlay.setEnabled(false) }
            preferences.edit().putBoolean("enabled", previouslyEnabled).commit()
            if (hadSpeed) context.speed = savedSpeed else context.sharedPrefs.edit().remove("speed").commit()
            if (!overlayWasAllowed) overlayPermission("default")
        }
    }
}
