package com.system.location.service

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.privacy.SdkPrivacy
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SdkPrivacyUiTest {
    @Before fun requireFreshPrivacyProcess() {
        assumeTrue("Run alone with -e freshPrivacy true", InstrumentationRegistry.getArguments().getString("freshPrivacy") == "true")
        assertFalse(SdkPrivacy.mapsAccepted)
        assertFalse("Runner must not initialize map SDK", SdkPrivacy.mapsReady)
        assertFalse(SdkPrivacy.crashReportingEnabled)
    }

    @Test fun freshPromptSurvivesRotationDeclineAndLaterExplicitAcceptance() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText(R.string.map_privacy_title)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check(matches(isDisplayed()))
            scenario.onActivity { assertTrue(it.supportFragmentManager.fragments.isEmpty()) }
            scenario.recreate()
            onView(withText(R.string.map_privacy_title)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check(matches(isDisplayed()))
            scenario.onActivity { assertTrue(it.supportFragmentManager.fragments.isEmpty()) }
            assertFalse(SdkPrivacy.mapsAccepted)
            assertFalse(SdkPrivacy.mapsReady)
            onView(withText(R.string.map_privacy_decline)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click())
            // finish() schedules lifecycle work; an idle main queue does not guarantee onDestroy.
            val deadline = android.os.SystemClock.uptimeMillis() + 5_000
            while (scenario.state != androidx.lifecycle.Lifecycle.State.DESTROYED &&
                android.os.SystemClock.uptimeMillis() < deadline) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                android.os.SystemClock.sleep(50)
            }
            assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, scenario.state)
            assertFalse(SdkPrivacy.mapsAccepted)
            assertFalse(SdkPrivacy.crashReportingEnabled)
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        listOf(android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.POST_NOTIFICATIONS).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, it)
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText(R.string.map_privacy_accept)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click())
            assertTrue(SdkPrivacy.mapsAccepted)
            assertTrue(SdkPrivacy.mapsReady)
            assertFalse(SdkPrivacy.crashReportingEnabled)
            assertFalse(SdkPrivacy.crashReportingRequested)
            scenario.recreate()
            assertTrue(SdkPrivacy.mapsAccepted)
            assertFalse(SdkPrivacy.crashReportingEnabled)
        }
    }
}
