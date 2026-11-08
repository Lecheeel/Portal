package com.system.location.service

import android.widget.Spinner
import androidx.navigation.findNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.tabs.TabLayout
import com.system.location.service.core.backend.BackendType
import com.system.location.service.runtime.ScenarioRuntime
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SimulationNavigationTest {
    @Before fun consent() { com.system.location.service.privacy.SdkPrivacy.acceptMaps() }

    @Test fun combinedTabsKeepCoordinatesAndRestoreSelectedTab() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findNavController(R.id.nav_host_fragment_content_main).navigate(R.id.nav_mock)
            }
            onView(withId(R.id.point_name)).check(matches(isDisplayed()))
            scenario.onActivity { activity ->
                assertNull(activity.binding.navView.menu.findItem(R.id.nav_gnss_mock))
                activity.findViewById<android.widget.EditText>(R.id.latitude).setText("26.123456")
                val tabs = activity.findViewById<TabLayout>(R.id.simulation_tabs)
                tabs.selectTab(tabs.getTabAt(1))
            }
            onView(withId(R.id.satellite_rada_view)).check(matches(isDisplayed()))
            scenario.recreate()
            onView(withId(R.id.satellite_rada_view)).check(matches(isDisplayed()))
            scenario.onActivity { activity ->
                val tabs = activity.findViewById<TabLayout>(R.id.simulation_tabs)
                assertEquals(1, tabs.selectedTabPosition)
                tabs.selectTab(tabs.getTabAt(0))
            }
            onView(withId(R.id.latitude)).check(matches(withText("26.123456")))
        }
    }

    @Test fun cancellingNativeSelectionKeepsActualBackendAcrossRecreation() {
        val original = ScenarioRuntime.state.value.backend
        assertNotEquals("Run on an idle non-Native fixture", BackendType.NATIVE, original)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findNavController(R.id.nav_host_fragment_content_main).navigate(R.id.nav_runtime)
            }
            onView(withId(R.id.backend)).perform(scrollTo()).check(matches(isDisplayed()))
            scenario.onActivity { activity ->
                activity.findViewById<Spinner>(R.id.backend).setSelection(BackendType.entries.indexOf(BackendType.NATIVE))
            }
            onView(withText(R.string.native_risk_title)).inRoot(isDialog()).check(matches(isDisplayed()))
            assertEquals(original, ScenarioRuntime.state.value.backend)
            scenario.recreate()
            onView(withText(R.string.native_risk_title)).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withText(android.R.string.cancel)).inRoot(isDialog()).perform(click())
            scenario.onActivity { activity ->
                assertEquals(original, ScenarioRuntime.state.value.backend)
                assertEquals(BackendType.entries.indexOf(original), activity.findViewById<Spinner>(R.id.backend).selectedItemPosition)
            }
        }
    }
}
