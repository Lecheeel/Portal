package com.system.location.service

import android.app.AppOpsManager
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
import com.system.location.service.core.runtime.RuntimePhase
import com.system.location.service.core.scenario.MovementProfile
import com.system.location.service.core.scenario.Scenario
import com.system.location.service.ext.*
import com.system.location.service.runtime.ScenarioRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SettingsRestoreUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun restoreWorksAfterStopAndSynchronizesSavedMotionWithThePage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        acceptSelectedMapForUiTest()
        grantUiPermissions()
        runBlocking { ScenarioRuntime.refreshDiagnostics().await() }
        assertFalse("Requires an inactive test fixture", ScenarioRuntime.state.value.isActive)
        val backend = ScenarioRuntime.state.value.backend
        val runtimePrefs = context.getSharedPreferences("scenario_runtime", 0)
        val originalRuntime = runtimePrefs.all
        val originalPreferences = context.sharedPrefs.all
        val config = File(context.noBackupFilesDir, "active-config.json")
        val originalConfig = config.takeIf { it.exists() }?.readBytes()
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val originalMode = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName)
        fun mockMode(mode: String) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "appops set ${context.packageName} android:mock_location $mode")).use { it.readBytes() }
        }
        try {
            mockMode("allow")
            context.gmsMockEnabled = false
            context.platformFusedEnabled = false
            context.startupBurstEnabled = false
            assertTrue(runBlocking { ScenarioRuntime.selectBackend(BackendType.MOCK_PROVIDER).await() })
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                val profile = MovementProfile(speedMps = 1.4, altitudeMeters = 123.5, accuracyMeters = 7f, intervalMs = 120, smoothMotion = true)
                assertTrue(runBlocking { ScenarioRuntime.start(Scenario("restore-ui", "恢复测试", point = Wgs84(30.0, 120.0), profile = profile)).await() })
                assertFalse("Mock Provider must not send Hook configuration", runBlocking { ScenarioRuntime.applyConfigNow() })
                assertFalse("Mock Provider must not start system GNSS mocking", runBlocking { ScenarioRuntime.setGnssMock(true).await() })
                assertTrue(runBlocking { ScenarioRuntime.stop().await() })
                assertEquals(RuntimePhase.STOPPED, ScenarioRuntime.state.value.phase)
                context.altitude = 999.0
                context.accuracy = 50f
                context.speed = 9.0
                context.reportDuration = 500
                context.smoothRouteMotion = false
                compose.openPage(activity, R.id.nav_settings)
                compose.scrollToText("恢复并启动上次场景").assertIsEnabled().performClick()
                compose.waitUntil(10_000) { ScenarioRuntime.state.value.phase == RuntimePhase.RUNNING }
                compose.onNodeWithText("恢复并启动上次场景").assertIsNotEnabled()
                assertEquals(123.5, context.altitude, 0.001)
                assertEquals(1.4, context.speed, 0.001)
                assertEquals(7f, context.accuracy)
                assertEquals(120, context.reportDuration)
                assertTrue(context.smoothRouteMotion)
                compose.scrollToText("定位海拔")
                compose.onNodeWithText("%.2f 米".format(123.5)).assertIsDisplayed()
                assertTrue(runBlocking { ScenarioRuntime.stop().await() })
                compose.scrollToText("恢复并启动上次场景").assertIsEnabled()
            }
        } finally {
            try {
                runBlocking { ScenarioRuntime.stop().await(); ScenarioRuntime.selectBackend(backend).await() }
                if (originalConfig == null) config.delete() else config.writeBytes(originalConfig)
                restore(context.sharedPrefs, originalPreferences)
                restore(runtimePrefs, originalRuntime)
            } finally {
                mockMode(when (originalMode) {
                    AppOpsManager.MODE_ALLOWED -> "allow"
                    AppOpsManager.MODE_IGNORED -> "ignore"
                    AppOpsManager.MODE_ERRORED -> "deny"
                    AppOpsManager.MODE_FOREGROUND -> "foreground"
                    else -> "default"
                })
            }
        }
    }

    private fun restore(preferences: SharedPreferences, values: Map<String, *>) {
        val editor = preferences.edit().clear()
        values.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> error("Unsupported fixture preference type")
            }
        }
        assertTrue(editor.commit())
    }
}
