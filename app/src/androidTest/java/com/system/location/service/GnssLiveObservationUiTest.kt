package com.system.location.service

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.runtime.ScenarioRuntime
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GnssLiveObservationUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    /** Restrict inspection to current GPS state; historical logs can contain old requests. */
    private fun gpsState(): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys location")
        val dump = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        return dump.substringAfter("    gps provider:").substringBefore("GNSS_KPI_START")
    }

    @Test fun realObservationStartsWithoutSimulationAndReleasesRequestsWhenHidden() {
        acceptSelectedMapForUiTest()
        grantUiPermissions()
        assertFalse("Run on an idle device", ScenarioRuntime.state.value.isActive)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.openPage(scenario, R.id.nav_mock)
            compose.waitUntil(5_000) { !gpsState().contains("com.system.location.service") }
            compose.onNodeWithText("卫星").performClick()
            compose.onNodeWithText("星图信息").assertExists()
            compose.waitUntil(5_000) { gpsState().contains("com.system.location.service") }
            assertFalse(ScenarioRuntime.state.value.isActive)
            // Signal acquisition depends on the physical environment; record the actual sky.
            Thread.sleep(10_000)
            compose.waitForIdle()
            captureUi("satellite-live-observation")
            compose.onNodeWithText("环境").performClick()
            compose.waitUntil(5_000) { !gpsState().contains("com.system.location.service") }
            compose.onNodeWithText("卫星").performClick()
            compose.waitUntil(5_000) { gpsState().contains("com.system.location.service") }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            compose.waitUntil(5_000) { !gpsState().contains("com.system.location.service") }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            compose.waitUntil(5_000) { gpsState().contains("com.system.location.service") }
        }
    }
}
