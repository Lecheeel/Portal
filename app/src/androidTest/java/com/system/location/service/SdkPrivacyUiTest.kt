package com.system.location.service

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
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun requireFreshPrivacyProcess() {
        assumeTrue("Run alone with -e freshPrivacy true", InstrumentationRegistry.getArguments().getString("freshPrivacy") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertFalse("Fresh privacy must exercise the unsaved provider default", context.sharedPrefs.contains("map_provider"))
        assertEquals(MapProvider.TENCENT, context.mapProvider)
        assertPrivacyState(tencentAccepted = false)
    }

    @Test fun freshPromptSurvivesRotationDeclineAndLaterExplicitAcceptance() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithText(context.getString(R.string.map_privacy_title)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.tencent_privacy_message)).assertIsDisplayed()
            scenario.onActivity { assertTrue(it.supportFragmentManager.fragments.isEmpty()) }
            assertPrivacyState(tencentAccepted = false)
            scenario.recreate()
            compose.onNodeWithText(context.getString(R.string.map_privacy_title)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.tencent_privacy_message)).assertIsDisplayed()
            scenario.onActivity { assertTrue(it.supportFragmentManager.fragments.isEmpty()) }
            assertPrivacyState(tencentAccepted = false)
            compose.onNodeWithText(context.getString(R.string.map_privacy_decline)).performClick()
            // finish() schedules lifecycle work; an idle main queue does not guarantee onDestroy.
            val deadline = android.os.SystemClock.uptimeMillis() + 5_000
            while (scenario.state != androidx.lifecycle.Lifecycle.State.DESTROYED &&
                android.os.SystemClock.uptimeMillis() < deadline) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                android.os.SystemClock.sleep(50)
            }
            assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, scenario.state)
            assertPrivacyState(tencentAccepted = false)
        }
        grantUiPermissions()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithText(context.getString(R.string.tencent_privacy_message)).assertIsDisplayed()
            scenario.onActivity { assertTrue(it.supportFragmentManager.fragments.isEmpty()) }
            assertPrivacyState(tencentAccepted = false)
            compose.onNodeWithText(context.getString(R.string.map_privacy_accept)).performClick()
            assertPrivacyState(tencentAccepted = true)
            scenario.recreate()
            assertPrivacyState(tencentAccepted = true)
        }
    }

    private fun assertPrivacyState(tencentAccepted: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            assertEquals(tencentAccepted, instrumentation.targetContext.getSharedPreferences("sdk_privacy", 0)
                .getBoolean("tencent_maps_v1", false))
            // ready() can initialize Tencent; observe the guard without changing SDK state.
            val initialized = TencentPrivacy::class.java.getDeclaredField("initialized").apply { isAccessible = true }
            assertEquals("Tencent initialization must follow explicit consent", tencentAccepted,
                initialized.getBoolean(TencentPrivacy))
            assertFalse("Tencent consent must not accept AMap", SdkPrivacy.mapsAccepted)
            assertFalse("Tencent consent must not initialize AMap", SdkPrivacy.mapsReady)
            assertFalse(SdkPrivacy.crashReportingEnabled)
            assertFalse(SdkPrivacy.crashReportingRequested)
        }
    }
}
