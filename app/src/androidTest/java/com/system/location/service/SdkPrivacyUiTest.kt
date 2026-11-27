package com.system.location.service

import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.Manifest.permission.POST_NOTIFICATIONS
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import org.junit.Rule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.ext.sharedPrefs
import com.system.location.service.maps.MapProvider
import com.system.location.service.maps.mapProvider
import com.system.location.service.privacy.SdkPrivacy
import com.system.location.service.privacy.TencentPrivacy
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
            scenario.recreate()
            compose.onNodeWithTag("startup-setup").assertDoesNotExist()
            assertPrivacyState(accepted = provider)
        }
    }

    private fun selectMap(tag: String) {
        compose.onNodeWithTag("startup-setup").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).assertIsDisplayed().performClick().assertIsSelected()
    }

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
