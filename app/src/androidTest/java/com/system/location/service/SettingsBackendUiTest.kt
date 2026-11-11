package com.system.location.service

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.system.location.service.core.backend.BackendType
import com.system.location.service.runtime.ScenarioRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI capability states only: backend selection never prepares or starts a scene. */
@RunWith(AndroidJUnit4::class)
class SettingsBackendUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun capabilitiesFollowSelectedBackendWithoutStartingSimulation() {
        acceptSelectedMapForUiTest()
        grantUiPermissions()
        assertFalse(ScenarioRuntime.state.value.isActive)
        val original = ScenarioRuntime.state.value.backend
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.openPage(scenario, R.id.nav_settings_gnss)
                for (backend in BackendType.entries) {
                    assertTrue(runBlocking { ScenarioRuntime.selectBackend(backend).await() })
                    compose.scrollToText("卫星生成模式")
                    if (backend == BackendType.MOCK_PROVIDER) {
                        compose.onNodeWithText("卫星生成模式", substring = true).assertIsNotEnabled()
                        compose.scrollToText("最少卫星数").assertIsNotEnabled()
                    } else {
                        compose.onNodeWithText("卫星生成模式", substring = true).assertIsEnabled()
                        compose.scrollToText("最少卫星数").assertIsEnabled()
                    }
                    compose.openPage(scenario, R.id.nav_settings_system)
                    compose.scrollToText("SIM 就绪")
                    val sim = compose.onNode(hasText("SIM 就绪") and isToggleable())
                    if (backend == BackendType.MOCK_PROVIDER) sim.assertIsNotEnabled() else sim.assertIsEnabled()
                    compose.openPage(scenario, R.id.nav_settings_motion)
                    compose.scrollToText("物理漂移噪声")
                    val noise = compose.onNode(hasText("物理漂移噪声") and isToggleable())
                    if (backend == BackendType.MOCK_PROVIDER) noise.assertIsNotEnabled() else noise.assertIsEnabled()
                    compose.openPage(scenario, R.id.nav_settings_apps)
                    compose.onNodeWithText("需 Xposed：", substring = true).assertIsDisplayed()
                    assertFalse("Reading capability states must not start a scene", ScenarioRuntime.state.value.isActive)
                    compose.openPage(scenario, R.id.nav_settings_gnss)
                }
            }
        } finally { assertTrue(runBlocking { ScenarioRuntime.selectBackend(original).await() }) }
    }
}
