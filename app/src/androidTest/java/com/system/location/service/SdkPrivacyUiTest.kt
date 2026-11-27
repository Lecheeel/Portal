package com.system.location.service

import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.Manifest.permission.POST_NOTIFICATIONS
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import org.junit.Rule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.ext.sharedPrefs
import com.system.location.service.core.backend.BackendType
import com.system.location.service.maps.MapProvider
import com.system.location.service.maps.mapProvider
import com.system.location.service.privacy.SdkPrivacy
import com.system.location.service.privacy.TencentPrivacy
import com.system.location.service.runtime.ScenarioRuntime
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SdkPrivacyUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Before fun requireFreshPrivacyProcess() {
        assumeTrue("Run alone with -e freshPrivacy true", InstrumentationRegistry.getArguments().getString("freshPrivacy") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertFalse("Fresh privacy must exercise the unsaved provider default", context.sharedPrefs.contains("map_provider"))
        assertEquals(MapProvider.TENCENT, context.mapProvider)
        assertPrivacyState(accepted = null)
    }

    @Test fun startupSetupGuidesPermissionsAndKeepsMapConsentSeparate() {
        val provider = MapProvider.restore(InstrumentationRegistry.getArguments().getString("startupMap"))
        val mapTag = "setup-map-${provider.name}"
        val continueButton = compose.onNodeWithTag("setup-continue")
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(ACCESS_FINE_LOCATION))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithText(context.getString(R.string.setup_title)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.setup_start_permissions)).assertIsDisplayed()
            continueButton.performClick()
            respondToDevicePermissions(allow = false)
            compose.onNodeWithText(context.getString(R.string.setup_permissions_denied)).assertExists()
            selectMap("setup-map-AMAP")
            scenario.onActivity { assertTrue(it.supportFragmentManager.fragments.isEmpty()) }
            assertPrivacyState(accepted = null)
            scenario.recreate()
            compose.onNodeWithTag("startup-setup").performScrollToNode(hasTestTag("setup-map-AMAP"))
            compose.onNodeWithTag("setup-map-AMAP").assertIsSelected()
            compose.onNodeWithText(context.getString(R.string.setup_enter)).assertDoesNotExist()
            assertPrivacyState(accepted = null)
        }
        // Leaving with a selected card never records consent.
        assertFalse(context.sharedPrefs.contains("map_provider"))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            continueButton.performClick()
            respondToDevicePermissions(allow = true)
            assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(ACCESS_FINE_LOCATION))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(POST_NOTIFICATIONS))
            }
            dismissBatteryPrompt()
            if (compose.onAllNodesWithTag("setup-skip-background").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithTag("setup-skip-background").performClick()
            }
            compose.onNodeWithTag("startup-setup").performScrollToNode(hasTestTag("setup-map-AMAP"))
            val tencent = compose.onNodeWithTag("setup-map-TENCENT").assertIsSelected()
            val amap = compose.onNodeWithTag("setup-map-AMAP").assertIsNotSelected()
            assertTrue(tencent.getUnclippedBoundsInRoot().top < amap.getUnclippedBoundsInRoot().top)
            if (provider == MapProvider.AMAP) selectMap(mapTag)
            assertPrivacyState(accepted = null)
            continueButton.assertIsEnabled().performClick()
            assertPrivacyState(accepted = provider)
            assertEquals(provider, context.mapProvider)
            compose.onNodeWithTag("startup-setup").assertDoesNotExist()
            compose.onNodeWithTag("startup-mode-setup").assertIsDisplayed()
            scenario.onActivity { assertTrue(it.supportFragmentManager.fragments.isEmpty()) }
            compose.onNodeWithText(context.getString(R.string.setup_modes_back)).performClick()
            compose.onNodeWithTag("startup-setup").performScrollToNode(hasTestTag(mapTag))
            compose.onNodeWithTag(mapTag).assertIsSelected()
            continueButton.assertIsEnabled().performClick()
            compose.onNodeWithTag("startup-mode-setup").assertIsDisplayed()
        }

        // A fresh Activity still blocks entry while no mode has been confirmed.
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val action = compose.onNodeWithTag("setup-mode-action")
            for (tag in listOf("setup-mode-KERNEL", "setup-mode-SHIZUKU")) {
                compose.onNodeWithTag("startup-mode-list").performScrollToNode(hasTestTag(tag))
                compose.onNodeWithTag(tag).assertIsNotEnabled()
            }
            selectBackend("setup-mode-XPOSED")
            assertModeHint(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) R.string.setup_modes_need_module
                else R.string.setup_modes_need_android)
            selectBackend("setup-mode-NATIVE")
            assertModeHint(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) R.string.setup_modes_need_arm64
                else R.string.setup_modes_need_android)
            selectBackend("setup-mode-STANDARD")
            scenario.onActivity { assertTrue(it.supportFragmentManager.fragments.isEmpty()) }
            val globalKey = Settings.Global.DEVELOPMENT_SETTINGS_ENABLED
            val developerSetting = Settings.Global.getString(context.contentResolver, globalKey)
            try {
                if (developerSetting?.toIntOrNull() != 1) {
                    assertModeHint(R.string.setup_modes_need_developer)
                    action.assertHasClickAction().performClick()
                    awaitSettings()
                    enableDeveloperOptions()
                    runShell("input keyevent KEYCODE_BACK")
                }
                assertModeHint(R.string.setup_modes_need_mock)
                action.assertHasClickAction().performClick()
                awaitSettings()
                runShell("input keyevent KEYCODE_BACK")
                assertModeHint(R.string.setup_modes_need_mock)
                scenario.onActivity { assertTrue(it.supportFragmentManager.fragments.isEmpty()) }

                action.performClick()
                awaitSettings()
                runShell("appops set ${context.packageName} android:mock_location allow")
                runShell("input keyevent KEYCODE_BACK")
                assertModeHint(R.string.setup_modes_ready)
                action.assertIsEnabled().performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("startup-mode-setup").fetchSemanticsNodes().isEmpty() }
                assertEquals(BackendType.MOCK_PROVIDER, ScenarioRuntime.state.value.backend)
                assertFalse(ScenarioRuntime.startupModePending())
            } finally {
                runShell("appops set ${context.packageName} android:mock_location default")
                if (developerSetting == null) runShell("settings delete global $globalKey")
                else if (developerSetting != "1") runShell("settings put global $globalKey $developerSetting")
            }
            scenario.recreate()
            compose.onNodeWithTag("startup-setup").assertDoesNotExist()
            compose.onNodeWithTag("startup-mode-setup").assertDoesNotExist()
            assertPrivacyState(accepted = provider)
        }
    }

    private fun selectMap(tag: String) {
        compose.onNodeWithTag("startup-setup").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).assertIsDisplayed().performClick().assertIsSelected()
    }

    private fun selectBackend(tag: String) {
        compose.onNodeWithTag("startup-mode-list").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick().assertIsSelected()
    }

    private fun assertModeHint(resource: Int) {
        val text = context.getString(resource)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    private fun awaitSettings() {
        val automation = instrumentation.uiAutomation
        val deadline = SystemClock.uptimeMillis() + 7_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (automation.rootInActiveWindow?.packageName?.toString()?.endsWith(".settings") == true) return
            SystemClock.sleep(100)
        }
        fail("System Settings did not open")
    }

    private fun enableDeveloperOptions() {
        val automation = instrumentation.uiAutomation
        val display = context.resources.displayMetrics
        val x = display.widthPixels / 2
        val deadline = SystemClock.uptimeMillis() + 7_000
        while (SystemClock.uptimeMillis() < deadline) {
            val title = automation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Build number")
                ?.firstOrNull { it.text?.toString() == "Build number" && it.isVisibleToUser }
            val row = generateSequence(title) { it.parent }.firstOrNull { it.isClickable }
            if (row != null) {
                repeat(7) {
                    assertTrue("Build number did not respond", row.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    SystemClock.sleep(180)
                }
                assertEquals(1, Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0))
                return
            }
            runShell("input swipe $x ${display.heightPixels * 3 / 4} $x ${display.heightPixels / 4} 250")
            SystemClock.sleep(250)
        }
        fail("Build number was not visible in About phone")
    }

    private fun runShell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command),
    ).use { it.readBytes() }

    private fun respondToDevicePermissions(allow: Boolean) {
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        var answeredLocation = false
        var answeredNotifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            val root = automation.rootInActiveWindow
            val location = root?.permissionNode("permission_allow_foreground_only_button")
            val notification = root?.permissionNode("permission_allow_button")
            val deny = root?.permissionNode("permission_deny_button")
            if (location != null && !answeredLocation) {
                if (allow) root.permissionNode("permission_location_accuracy_radio_fine")
                    ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                answeredLocation = (if (allow) location else deny)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            } else if (location == null && notification != null && !answeredNotifications) {
                answeredNotifications = (if (allow) notification else deny)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            if (answeredLocation && answeredNotifications) return
            SystemClock.sleep(100)
        }
        error("Device dialogs not answered: location=$answeredLocation, notifications=$answeredNotifications")
    }

    private fun AccessibilityNodeInfo.permissionNode(id: String): AccessibilityNodeInfo? =
        // Google PermissionController and its resource namespace may differ.
        findAccessibilityNodeInfosByViewId("com.android.permissioncontroller:id/$id").firstOrNull()
            ?: findAccessibilityNodeInfosByViewId("$packageName:id/$id").firstOrNull()

    private fun dismissBatteryPrompt() {
        val power = context.getSystemService(android.os.PowerManager::class.java)
        if (power.isIgnoringBatteryOptimizations(context.packageName)) return
        val automation = instrumentation.uiAutomation
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (automation.rootInActiveWindow?.packageName?.toString()?.endsWith(".settings") == true) {
                ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input keyevent KEYCODE_BACK"))
                    .use { it.readBytes() }
                return
            }
            SystemClock.sleep(100)
        }
        error("Battery optimization settings did not open after device permissions")
    }

    private fun assertPrivacyState(accepted: MapProvider?) {
        instrumentation.runOnMainSync {
            assertEquals(accepted == MapProvider.TENCENT, context.getSharedPreferences("sdk_privacy", 0)
                .getBoolean("tencent_maps_v1", false))
            // ready() can initialize Tencent; observe the guard without changing SDK state.
            val initialized = TencentPrivacy::class.java.getDeclaredField("initialized").apply { isAccessible = true }
            assertEquals("Tencent initialization must follow explicit consent", accepted == MapProvider.TENCENT,
                initialized.getBoolean(TencentPrivacy))
            assertEquals(accepted == MapProvider.AMAP, SdkPrivacy.mapsAccepted)
            assertEquals(accepted == MapProvider.AMAP, SdkPrivacy.mapsReady)
            assertFalse(SdkPrivacy.crashReportingEnabled)
            assertFalse(SdkPrivacy.crashReportingRequested)
        }
    }
}
