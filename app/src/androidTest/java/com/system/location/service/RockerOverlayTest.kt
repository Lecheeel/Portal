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
import androidx.compose.ui.test.*
import org.junit.Rule
import androidx.navigation.findNavController
import androidx.test.core.app.ActivityScenario
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
    private fun awaitOverlay(tag: String) {
        // The composition can be idle before WindowManager lays out its independent window.
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag(tag).assertIsDisplayed(); true }.getOrDefault(false)
        }
    }
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
                awaitOverlay("rocker_expand")
                captureUi("overlay-minimized")
                compose.onNodeWithTag("rocker_expand").performClick()
                awaitOverlay("rocker_direction")
                compose.onNodeWithTag("rocker_speed_slider").assertDoesNotExist()
                captureUi("overlay-expanded")
                compose.onNodeWithTag("rocker_speed_toggle").performClick()
                awaitOverlay("rocker_speed_slider")
                compose.onNodeWithTag("rocker_speed_slider").assertContentDescriptionEquals(context.getString(R.string.rocker_speed))
                compose.onNodeWithTag("rocker_speed_slider").assertIsDisplayed()
                captureUi("overlay-speed-expanded")
                compose.onNodeWithTag("rocker_speed_up").performClick()
                runBlocking { withTimeout(5000) { while (kotlin.math.abs(context.speed - 3.15) > 0.001) delay(10) } }
                compose.onNodeWithText(context.getString(R.string.overlay_speed_number, context.speed)).assertIsDisplayed()
                compose.onNodeWithTag("rocker_speed_down").performClick()
                runBlocking { withTimeout(5000) { while (kotlin.math.abs(context.speed - 3.05) > 0.001) delay(10) } }
                compose.onNodeWithText(context.getString(R.string.speed_kmh, context.speed * 3.6)).assertIsDisplayed()
                compose.onNodeWithContentDescription(context.getString(R.string.overlay_minimize)).performClick()
                awaitOverlay("rocker_expand")
                compose.onNodeWithTag("rocker_expand").performClick()
                awaitOverlay("rocker_direction")
                compose.onNodeWithTag("rocker_speed_slider").assertDoesNotExist()
                compose.onNodeWithTag("rocker_speed_toggle").assertTextContains(context.getString(R.string.overlay_speed_value, context.speed))
                compose.onNodeWithContentDescription(context.getString(R.string.overlay_minimize)).performClick()
                scenario.onActivity { it.findNavController(R.id.nav_host_fragment_content_main).navigate(R.id.nav_runtime) }
                scenario.recreate()
                awaitOverlay("rocker_expand")
                // Stopping even an idle scenario must not dismiss the independent overlay.
                runBlocking { ScenarioRuntime.stop().await() }
                compose.onNodeWithTag("rocker_expand").assertIsDisplayed()
                compose.openPage(scenario, R.id.nav_home)
                compose.onNodeWithContentDescription("关闭悬浮窗").performClick()
                scenario.onActivity {
                    assertFalse(RockerOverlay.isEnabled)
                    assertFalse(RockerOverlay.isVisible)
                }
                scenario.recreate()
                scenario.onActivity { assertFalse(RockerOverlay.isVisible) }
                compose.onNodeWithContentDescription("打开悬浮窗").performClick()
                awaitOverlay("rocker_expand")
                repeat(2) {
                    compose.onNodeWithContentDescription("关闭悬浮窗").performClick()
                    compose.onNodeWithTag("rocker_expand").assertDoesNotExist()
                    compose.onNodeWithContentDescription("打开悬浮窗").performClick()
                    awaitOverlay("rocker_expand")
                }
            }
        } finally {
            instrumentation.runOnMainSync { RockerOverlay.setEnabled(false) }
            preferences.edit().putBoolean("enabled", previouslyEnabled).commit()
            if (hadSpeed) context.speed = savedSpeed else context.sharedPrefs.edit().remove("speed").commit()
            if (!overlayWasAllowed) overlayPermission("default")
        }
    }
}
