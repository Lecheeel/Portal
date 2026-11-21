package com.system.location.service

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeSearchUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun searchExpandsFromTheRightRestoresDraftAndCollapsesBeforeNavigation() {
        acceptSelectedMapForUiTest()
        grantUiPermissions()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithTag("home-search-input").assertDoesNotExist()
            val menu = compose.onNodeWithContentDescription("打开菜单").assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
            val search = compose.onNodeWithContentDescription("搜索地点").assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertTrue(menu.right < search.left)
            compose.onNodeWithContentDescription("搜索地点").performClick()
            compose.onNodeWithTag("home-search-input").assertIsDisplayed().performTextInput("上海人民广场")
            assertEquals(menu, compose.onNodeWithContentDescription("打开菜单").fetchSemanticsNode().boundsInRoot)
            assertEquals(search, compose.onNodeWithContentDescription("搜索地点").fetchSemanticsNode().boundsInRoot)
            scenario.recreate()
            compose.onNodeWithTag("home-search-input").assertTextContains("上海人民广场")
            compose.onNodeWithContentDescription("收起搜索").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("home-search-input").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithContentDescription("搜索地点").performClick()
            compose.onNodeWithTag("home-search-input").assertTextContains("上海人民广场")
            scenario.onActivity { it.systemBackForUiTest() }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("home-search-input").fetchSemanticsNodes().isEmpty() }
            scenario.onActivity { assertEquals(R.id.nav_home, it.currentDestinationId) }
            compose.onNodeWithContentDescription("搜索地点").performClick()
            compose.onNodeWithContentDescription("打开菜单").performClick()
            compose.onNodeWithTag("home-search-input").assertDoesNotExist()
            compose.onNodeWithTag("portal_drawer").assertIsDisplayed()
        }
    }
}
