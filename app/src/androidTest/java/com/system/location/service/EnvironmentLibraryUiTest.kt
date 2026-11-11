package com.system.location.service

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.core.environment.EnvironmentProfile
import com.system.location.service.core.environment.EnvironmentSourceType
import com.system.location.service.environment.EnvironmentStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EnvironmentLibraryUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun profile() = EnvironmentProfile(source = EnvironmentSourceType.REPLAY,
        generatedAtMillis = 1, seed = 42)

    @Test fun searchAndRenameDraftSurviveRecreationAndDeleteRequiresConfirmation() {
        acceptSelectedMapForUiTest()
        grantUiPermissions()
        val store = EnvironmentStore(context)
        val name = "ui-library-${UUID.randomUUID()}"
        val renamed = "$name-new"
        try {
            store.save(name, profile())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.openPage(scenario, R.id.nav_environment_library)
                compose.onNodeWithText("搜索已保存环境").performTextInput(name)
                compose.waitUntil(10_000) { compose.onAllNodes(hasText(name) and hasClickAction() and hasSetTextAction().not()).fetchSemanticsNodes().isNotEmpty() }
                scenario.recreate()
                compose.onNodeWithText("搜索已保存环境").assertTextContains(name)
                compose.waitUntil(10_000) { compose.onAllNodes(hasText(name) and hasClickAction() and hasSetTextAction().not()).fetchSemanticsNodes().isNotEmpty() }
                compose.onNode(hasText(name) and hasClickAction() and hasSetTextAction().not()).performClick()
                compose.onNodeWithText("重命名").performClick()
                compose.onNodeWithText("环境名称").performTextReplacement(renamed)
                scenario.recreate()
                compose.onNodeWithText("环境名称").assertTextContains(renamed)
                compose.onNodeWithText("保存").performClick()
                compose.waitUntil(10_000) { renamed in store.list() }
                compose.waitUntil(10_000) { compose.onAllNodes(hasText(renamed) and hasClickAction() and hasSetTextAction().not()).fetchSemanticsNodes().isNotEmpty() }
                compose.onNode(hasText(renamed) and hasClickAction() and hasSetTextAction().not()).performClick()
                compose.onNodeWithText("删除").performClick()
                compose.onNodeWithText("取消").performClick()
                assertTrue(renamed in store.list())
                compose.onNode(hasText(renamed) and hasClickAction() and hasSetTextAction().not()).performClick()
                compose.onNodeWithText("删除").performClick()
                compose.onNodeWithText("确认删除").performClick()
                compose.waitUntil(10_000) { renamed !in store.list() }
                compose.waitUntil(10_000) { compose.onAllNodesWithText("没有符合条件的环境").fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodesWithText("还没有保存环境").fetchSemanticsNodes().isNotEmpty() }
            }
        } finally { store.delete(name); store.delete(renamed) }
    }

    @Test fun unreadableListShowsRetryAndRecoversWithoutReplacingFiles() {
        acceptSelectedMapForUiTest()
        grantUiPermissions()
        val directory = File(context.filesDir, "environment")
        val backup = File(context.filesDir, "environment-ui-backup-${UUID.randomUUID()}")
        val existed = directory.exists()
        if (existed) assertTrue(directory.renameTo(backup))
        val name = "ui-library-retry"
        try {
            directory.writeText("blocked directory")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.openPage(scenario, R.id.nav_environment_library)
                compose.waitUntil(10_000) { compose.onAllNodesWithText("重新加载").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("还没有保存环境").assertDoesNotExist()
                assertEquals("blocked directory", directory.readText())
                assertTrue(directory.delete())
                EnvironmentStore(context).save(name, profile())
                compose.onNodeWithText("重新加载").performClick()
                compose.waitUntil(10_000) { compose.onAllNodes(hasText(name) and hasClickAction() and hasSetTextAction().not()).fetchSemanticsNodes().isNotEmpty() }
                captureUi("environment-library-search")
            }
        } finally {
            if (directory.isDirectory) { EnvironmentStore(context).delete(name); assertTrue(directory.delete()) }
            else if (directory.exists()) assertTrue(directory.delete())
            if (existed) assertTrue(backup.renameTo(directory))
        }
    }
}
