package com.system.location.service

import android.app.AppOpsManager
import android.content.Context
import android.content.SharedPreferences
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.core.backend.BackendType
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.repository.SavedLocation
import com.system.location.service.core.repository.SavedRoute
import com.system.location.service.core.runtime.FileConfigStore
import com.system.location.service.core.runtime.RuntimePhase
import com.system.location.service.core.runtime.RuntimeState
import com.system.location.service.core.scenario.Route
import com.system.location.service.core.scenario.RouteMode
import com.system.location.service.data.repository.LibraryRepositories
import com.system.location.service.ext.sharedPrefs
import com.system.location.service.runtime.ScenarioRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HomeSelectionUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Before fun fixture() {
        com.system.location.service.privacy.SdkPrivacy.acceptMaps()
        grantUiPermissions()
    }

    @Test fun savedRouteThenLocationRequireExplicitStartAndKeepTheirScenarioType() = withSavedTargets { location, route ->
        withMockRuntime {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                selectSavedTarget("路线", route.route.name)
                assertSelectionWithoutStart(route.route.name)
                scenario.recreate()
                assertSelectionWithoutStart(route.route.name)
                compose.onNodeWithText("开始模拟").assertIsEnabled().performClick()
                awaitRunning(route.route.name)
                val routeRuntime = ScenarioRuntime.state.value
                assertEquals(route.route.id, routeRuntime.scenarioId)
                assertEquals("A saved route must run as a route, not its first point", route.route.id, routeRuntime.routeId)
                assertCoordinate(route.route.points.first(), routeRuntime)
                val runningRouteId = routeRuntime.scenarioId
                scenario.recreate()
                assertEquals("Recreation must retain the active route session", runningRouteId, ScenarioRuntime.state.value.scenarioId)
                assertEquals(RuntimePhase.RUNNING, ScenarioRuntime.state.value.phase)
                compose.onNodeWithText("运行中").assertIsNotEnabled()
                stopFromHome()
                val savedRouteScenario = checkNotNull(FileConfigStore(File(context.noBackupFilesDir, "active-config.json")).load()).scenario
                assertEquals(route.route, savedRouteScenario.route)
                assertEquals(route.mode, savedRouteScenario.mode)

                selectSavedTarget("地点", location.name)
                assertSelectionWithoutStart(location.name)
                scenario.recreate()
                assertSelectionWithoutStart(location.name)
                compose.onNodeWithText("开始模拟").assertIsEnabled().performClick()
                awaitRunning(location.name)
                val pointRuntime = ScenarioRuntime.state.value
                assertNull("Choosing a location must clear the previous route target", pointRuntime.routeId)
                assertCoordinate(location.coordinate, pointRuntime)
                stopFromHome()
                val savedPointScenario = checkNotNull(FileConfigStore(File(context.noBackupFilesDir, "active-config.json")).load()).scenario
                assertNull(savedPointScenario.route)
                assertEquals(location.coordinate, savedPointScenario.point)
            }
        }
    }

    @Test fun dismissingRecreatedPickerKeepsTheSelectedRouteWithoutStarting() = withSavedTargets { location, route ->
        withMockRuntime {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                selectSavedTarget("路线", route.route.name)
                assertSelectionWithoutStart(route.route.name)
                openPicker()
                compose.onNode(hasText("路线") and hasAnyAncestor(isDialog())).performClick().assertIsSelected()
                scenario.recreate()
                compose.onNode(hasText("路线") and hasAnyAncestor(isDialog())).assertIsSelected()
                compose.scrollTo(hasText(route.route.name) and hasAnyAncestor(isDialog())).assertIsDisplayed()
                compose.onNodeWithText("关闭").performClick()
                assertSelectionWithoutStart(route.route.name)
                selectSavedTarget("地点", location.name)
                assertSelectionWithoutStart(location.name)
            }
        }
    }

    private fun openPicker() {
        compose.onNode(hasContentDescription("选择模拟目标") or hasContentDescription("选择地点或路线"))
            .assertIsDisplayed().performClick()
    }

    private fun selectSavedTarget(tab: String, name: String) {
        openPicker()
        compose.onNode(hasText(tab) and hasAnyAncestor(isDialog())).performClick()
        compose.scrollTo(hasText(name) and hasAnyAncestor(isDialog())).performClick()
        compose.onNode(hasText(name) and hasAnyAncestor(isDialog())).assertDoesNotExist()
    }

    private fun assertSelectionWithoutStart(name: String) {
        compose.onNodeWithText(name).assertIsDisplayed()
        compose.onNodeWithText("开始模拟").assertIsEnabled()
        compose.onNodeWithText("停止模拟").assertIsNotEnabled()
        assertFalse("Selecting or restoring a saved target must never start simulation", ScenarioRuntime.state.value.isActive)
    }

    private fun awaitRunning(name: String) {
        compose.waitUntil(15_000) {
            val state = ScenarioRuntime.state.value
            assertNotEquals("Home start failed: ${state.error}", RuntimePhase.ERROR, state.phase)
            state.phase == RuntimePhase.RUNNING && state.scenarioName == name && state.sample != null
        }
        assertEquals(name, ScenarioRuntime.state.value.scenarioName)
    }

    private fun assertCoordinate(expected: Wgs84, state: RuntimeState) {
        val sample = checkNotNull(state.sample) { "Running target must publish a sample" }
        assertEquals(expected.latitude, sample.coordinate.latitude, 0.000001)
        assertEquals(expected.longitude, sample.coordinate.longitude, 0.000001)
    }

    private fun stopFromHome() {
        compose.onNodeWithText("停止模拟").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { !ScenarioRuntime.state.value.isActive }
        assertEquals(RuntimePhase.STOPPED, ScenarioRuntime.state.value.phase)
        compose.onNodeWithText("开始模拟").assertIsEnabled()
    }

    /** Append uniquely named records so existing user data cannot affect selection assertions. */
    private fun withSavedTargets(test: (SavedLocation, SavedRoute) -> Unit) {
        LibraryRepositories.ensureMigrated()
        val locations = LibraryRepositories.locations.list()
        val routes = LibraryRepositories.routes.list()
        val token = UUID.randomUUID().toString()
        val location = SavedLocation("home-point-$token", "Home point $token", Wgs84(25.123456, 119.123456), "Home test address")
        val route = SavedRoute(Route("home-route-$token", "Home route $token",
            listOf(Wgs84(26.1, 120.1), Wgs84(26.2, 120.2))), mode = RouteMode.LOOP)
        val selected = context.sharedPrefs.snapshot("selectedLocation", "selectedRoute")
        try {
            LibraryRepositories.locations.create(location)
            LibraryRepositories.routes.create(route)
            test(location, route)
        } finally {
            try { LibraryRepositories.locations.replaceAll(locations) }
            finally {
                try { LibraryRepositories.routes.replaceAll(routes) }
                finally { context.sharedPrefs.restore(selected) }
            }
        }
    }

    /** Exercise the real foreground service and Mock Provider; never replace runtime responses. */
    private fun withMockRuntime(test: () -> Unit) {
        runBlocking { ScenarioRuntime.refreshDiagnostics().await() }
        assertFalse("Selection tests require an inactive runtime", ScenarioRuntime.state.value.isActive)
        val backend = ScenarioRuntime.state.value.backend
        val preferences = context.getSharedPreferences("scenario_runtime", Context.MODE_PRIVATE)
        val runtimePreferences = preferences.snapshot("backend", "interrupted")
        val motionKeys = arrayOf("gmsMockEnabled", "platformFusedEnabled", "experimentalOrbitMotion", "startupBurstEnabled", "speed")
        val motionPreferences = context.sharedPrefs.snapshot(*motionKeys)
        val config = File(context.noBackupFilesDir, "active-config.json")
        val originalConfig = config.takeIf { it.exists() }?.readBytes()
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val previousMockMode = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName)
        fun setMockMode(mode: String) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "appops set ${context.packageName} android:mock_location $mode")).use { it.readBytes() }
        }
        try {
            setMockMode("allow")
            assertEquals("Mock authorization must be effective", AppOpsManager.MODE_ALLOWED,
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName))
            context.sharedPrefs.edit().putBoolean("gmsMockEnabled", false).putBoolean("platformFusedEnabled", false)
                .putBoolean("experimentalOrbitMotion", false).putBoolean("startupBurstEnabled", false).putFloat("speed", 0f)
                .commit().also { assertTrue("Cannot save deterministic motion preferences", it) }
            assertTrue(runBlocking { ScenarioRuntime.selectBackend(BackendType.MOCK_PROVIDER).await() })
            test()
        } finally {
            try {
                assertTrue("Test runtime must release its providers", runBlocking { ScenarioRuntime.stop().await() })
                assertFalse(ScenarioRuntime.state.value.isActive)
                assertTrue("Original backend must be restored", runBlocking { ScenarioRuntime.selectBackend(backend).await() })
            } finally {
                try {
                    try {
                        if (originalConfig == null) {
                            assertTrue("Test config must be removed", !config.exists() || config.delete())
                        } else config.writeBytes(originalConfig)
                    } finally {
                        try { context.sharedPrefs.restore(motionPreferences) }
                        finally { preferences.restore(runtimePreferences) }
                    }
                } finally {
                    setMockMode(when (previousMockMode) {
                        AppOpsManager.MODE_ALLOWED -> "allow"
                        AppOpsManager.MODE_IGNORED -> "ignore"
                        AppOpsManager.MODE_ERRORED -> "deny"
                        AppOpsManager.MODE_FOREGROUND -> "foreground"
                        else -> "default"
                    })
                }
            }
        }
    }

    private fun SharedPreferences.snapshot(vararg keys: String): Map<String, Any?> =
        keys.associateWith { all[it] }

    private fun SharedPreferences.restore(values: Map<String, Any?>) {
        val editor = edit()
        values.forEach { (key, value) ->
            when (value) {
                null -> editor.remove(key)
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                else -> error("Unexpected fixture preference type for $key")
            }
        }
        assertTrue("Fixture preferences must be restored", editor.commit())
    }
}
