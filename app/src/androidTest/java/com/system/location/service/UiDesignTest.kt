package com.system.location.service

import android.view.View
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import org.junit.Rule
import androidx.appcompat.app.AppCompatDelegate
import androidx.navigation.findNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.core.runtime.RuntimePhase
import com.system.location.service.core.runtime.RuntimeState
import com.system.location.service.core.runtime.SubmissionHealth
import com.system.location.service.ui.displayStates
import com.system.location.service.maps.PortalMapView
import com.system.location.service.data.repository.LibraryRepositories
import com.system.location.service.core.repository.SavedRoute
import com.system.location.service.core.scenario.Route
import com.system.location.service.core.geo.Wgs84
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiDesignTest {
    @get:Rule val compose = createEmptyComposeRule()
    @org.junit.Before fun acceptMapConsentForThisFixture() {
        com.system.location.service.privacy.SdkPrivacy.acceptMaps()
    }
    @Test fun homeHasNoToolbarGapAfterReturningFromEveryOtherPage() {
        permissions()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun homeGeometry(): List<Int> {
                compose.onNode(hasSetTextAction()).assertIsDisplayed()
                compose.onNodeWithText("开始模拟").assertIsDisplayed()
                val search = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
                val bottom = compose.onNodeWithText("开始模拟").fetchSemanticsNode().boundsInRoot
                var geometry = emptyList<Int>()
                scenario.onActivity { activity ->
                    val map = activity.window.decorView.descendantsOfType<PortalMapView>().filter { it.isAttachedToWindow }.single()
                    val mapPosition = IntArray(2).also { map.getLocationOnScreen(it) }
                    val rootPosition = IntArray(2).also { activity.binding.drawerLayout.getLocationOnScreen(it) }
                    assertArrayEquals("Map must start at the full-screen content origin", rootPosition, mapPosition)
                    assertEquals("Map must fill the content width", activity.binding.drawerLayout.width, map.width)
                    assertEquals("Map must extend behind the action panel", activity.binding.drawerLayout.height, map.height)
                    assertEquals(View.GONE, activity.binding.appBarMain.appBarLayout.visibility)
                    geometry = listOf(mapPosition[0], mapPosition[1], map.width, map.height, search.top.toInt(), bottom.bottom.toInt())
                }
                return geometry
            }
            val initial = homeGeometry()
            for (destination in listOf(R.id.nav_mock, R.id.nav_runtime, R.id.nav_gnss_mock,
                R.id.nav_route_gallery, R.id.nav_settings, R.id.nav_route_edit)) {
                for (useDrawer in listOf(true, false)) {
                    scenario.onActivity { activity ->
                        activity.findNavController(R.id.nav_host_fragment_content_main).navigate(destination)
                    }
                    onView(withId(R.id.toolbar)).check(matches(isDisplayed()))
                    if (useDrawer) {
                        scenario.onActivity { activity ->
                            activity.binding.navView.menu.performIdentifierAction(R.id.nav_home, 0)
                        }
                    } else {
                        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                    }
                    assertEquals("Home geometry changed after destination $destination (drawer=$useDrawer)", initial, homeGeometry())
                }
            }
            scenario.onActivity { it.findNavController(R.id.nav_host_fragment_content_main).navigate(R.id.nav_mock) }
            onView(withId(R.id.toolbar)).check(matches(isDisplayed()))
            scenario.recreate()
            onView(withId(R.id.toolbar)).check(matches(isDisplayed()))
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            assertEquals(initial, homeGeometry())
            capture("home-after-navigation")
        }
    }

    @Test fun drawerReturnsToMockAfterOpeningLibraryAndAfterRecreation() {
        permissions()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun openMock() {
                scenario.onActivity { activity ->
                    activity.binding.navView.menu.performIdentifierAction(R.id.nav_mock, 0)
                }
                compose.scrollToText("位置名称").assertIsDisplayed()
                scenario.onActivity {
                    assertEquals(R.id.nav_mock, it.findNavController(R.id.nav_host_fragment_content_main).currentDestination?.id)
                }
            }
            openMock()
            repeat(2) {
                compose.scrollToText("打开资料库").performClick()
                compose.scrollToText("搜索资料").assertIsDisplayed()
                if (it == 0) scenario.recreate()
                openMock()
            }
            compose.scrollToText("打开资料库").performClick()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.scrollToText("位置名称").assertIsDisplayed()
        }
    }

    @Test fun edgeToEdgeKeepsMapFullSizeAndControlsInsideSafeInsets() {
        permissions()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNode(hasSetTextAction()).assertIsDisplayed()
            val insets = androidx.core.view.WindowInsetsCompat.Builder()
                .setInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars(), androidx.core.graphics.Insets.of(12, 48, 16, 32))
                .build()
            fun applyInsets() {
                scenario.onActivity { androidx.core.view.ViewCompat.dispatchApplyWindowInsets(it.binding.appBarMain.root, insets) }
                compose.waitForIdle()
            }
            applyInsets()
            val search = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
            val start = compose.onNodeWithText("开始模拟").fetchSemanticsNode().boundsInRoot
            var mapWidth = 0
            var mapHeight = 0
            scenario.onActivity { activity ->
                val container = activity.binding.appBarMain.root
                assertEquals(0, container.paddingTop)
                assertEquals(0, container.paddingBottom)
                val map = activity.window.decorView.descendantsOfType<PortalMapView>().filter { it.isAttachedToWindow }.single()
                mapWidth = map.width
                mapHeight = activity.binding.drawerLayout.height
                val mapPosition = IntArray(2).also { map.getLocationOnScreen(it) }
                val rootPosition = IntArray(2).also { activity.binding.drawerLayout.getLocationOnScreen(it) }
                assertArrayEquals("Insets must not move the full-screen map", rootPosition, mapPosition)
                assertEquals("Insets must not shrink the map width", activity.binding.drawerLayout.width, map.width)
                assertEquals("Map must remain full-height behind the controls", mapHeight, map.height)
                assertEquals(View.GONE, activity.binding.appBarMain.appBarLayout.visibility)
            }
            assertTrue("Search must avoid the status bar", search.top >= 48f)
            assertTrue("Search must avoid side insets", search.left >= 12f && search.right <= mapWidth - 16f)
            assertTrue("Start action must avoid navigation bar", start.bottom <= mapHeight - 32f)
            applyInsets()
            assertEquals(search, compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot)
            assertEquals(start, compose.onNodeWithText("开始模拟").fetchSemanticsNode().boundsInRoot)
            compose.openPage(scenario, R.id.nav_mock)
            applyInsets()
            scenario.onActivity { activity ->
                val container = activity.binding.appBarMain.root
                assertEquals(48, container.paddingTop)
                assertEquals(32, container.paddingBottom)
                assertEquals(12, container.paddingLeft)
                assertEquals(16, container.paddingRight)
                assertEquals(View.VISIBLE, activity.binding.appBarMain.toolbar.visibility)
            }
        }
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun permissions() = grantUiPermissions()

    @Test fun sampleRedrawsAreBoundedWithoutDelayingPauseErrorsOrBackendChanges() = runBlocking {
        val initial = RuntimeState(phase = RuntimePhase.RUNNING)
        val rendered = flow {
            emit(initial)
            repeat(100) { emit(initial.copy(submissionHealth = SubmissionHealth(count = it.toLong()))) }
            emit(initial.copy(phase = RuntimePhase.PAUSED))
            emit(initial.copy(phase = RuntimePhase.ERROR))
            emit(initial.copy(backend = com.system.location.service.core.backend.BackendType.XPOSED))
        }.displayStates().toList()
        assertEquals(RuntimePhase.RUNNING, rendered.first().phase)
        assertTrue(rendered.size < 10)
        assertEquals(listOf(RuntimePhase.PAUSED, RuntimePhase.ERROR, RuntimePhase.RUNNING), rendered.takeLast(3).map { it.phase })
        assertEquals(com.system.location.service.core.backend.BackendType.XPOSED, rendered.last().backend)
    }

    @Test fun librarySearchCategoriesAndTransferActionsRemainAccessible() {
        permissions()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.openPage(scenario, R.id.nav_route_gallery)
            compose.scrollToText("搜索资料").performTextReplacement("不会匹配的记录")
            compose.scrollToText("没有匹配的资料").assertIsDisplayed()
            compose.scrollToText("搜索资料").performTextReplacement("")
            compose.scrollToText("位置").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("位置") and isSelected()).fetchSemanticsNodes().isNotEmpty() }
            compose.scrollToText("位置").assertIsSelected()
            val more = compose.scrollToText("更多")
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("更多") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            more.assertIsEnabled().performClick()
            compose.onNodeWithText("导入 JSON").assertIsDisplayed()
            compose.onNodeWithText("导出 JSON").assertIsDisplayed()
            compose.onNodeWithText("导入 GPX").assertDoesNotExist()
            compose.onNodeWithText("取消").performClick()
            capture("library-empty")
        }
    }

    @Test fun largeLibraryUsesLazyRowsAndKeepsTheSelectedItemIdentity() {
        permissions()
        LibraryRepositories.ensureMigrated()
        val original = LibraryRepositories.routes.list()
        try {
            LibraryRepositories.routes.replaceAll((0 until 1000).map {
                SavedRoute(Route("ui-$it", String.format(java.util.Locale.ROOT, "UI route %04d", it), listOf(Wgs84(1.0, 2.0), Wgs84(1.1, 2.1))))
            })
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.openPage(scenario, R.id.nav_route_gallery)
                compose.scrollToText("UI route 0000").assertIsDisplayed()
                fun assertLazyRows() {
                    val rows = compose.onAllNodes(hasText("UI route ", substring = true) and hasClickAction()).fetchSemanticsNodes()
                    assertTrue("Only visible/prefetched rows should be composed, got ${rows.size}", rows.size in 1..20)
                }
                assertLazyRows()
                compose.scrollToText("UI route 0000").performClick()
                compose.onNode(hasText("UI route 0000") and hasAnyAncestor(isDialog())).assertIsDisplayed()
                compose.onNodeWithText("取消").performClick()
                compose.scrollToText("UI route 0999").performClick()
                compose.onNode(hasText("UI route 0999") and hasAnyAncestor(isDialog())).assertIsDisplayed()
                compose.onNodeWithText("重命名").performClick()
                compose.onNodeWithText("名称").performTextReplacement("UI renamed last")
                compose.onNodeWithText("保存").performClick()
                compose.waitUntil(5_000) { LibraryRepositories.routes.get("ui-999")?.route?.name == "UI renamed last" }
                assertEquals("UI route 0000", LibraryRepositories.routes.get("ui-0")?.route?.name)
                assertEquals(1000, LibraryRepositories.routes.list().size)
                compose.waitUntil(10_000) {
                    var renamed = false
                    scenario.onActivity { activity ->
                        val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main)!!
                        val page = host.childFragmentManager.primaryNavigationFragment!!
                        val model = androidx.lifecycle.ViewModelProvider(page)[com.system.location.service.ui.viewmodel.LibraryViewModel::class.java]
                        renamed = !model.state.value.busy && model.state.value.items.any { it.id == "ui-999" && it.title == "UI renamed last" }
                    }
                    renamed
                }
                // Renaming changes the sorted position; restart traversal from the top
                // after the published UI list reflects the completed repository operation.
                compose.waitForIdle()
                compose.onNode(hasScrollToNodeAction()).performScrollToIndex(0)
                compose.scrollToText("UI renamed last").assertIsDisplayed()
                assertLazyRows()
                capture("library-populated")
            }
        } finally { LibraryRepositories.routes.replaceAll(original) }
    }

    @Test fun primaryPagesRenderInLightAndDarkThemes() {
        permissions()
        val oldMode = AppCompatDelegate.getDefaultNightMode()
        val oldSystemMode = android.provider.Settings.Secure.getInt(instrumentation.targetContext.contentResolver, "ui_night_mode", 1)
        fun systemTheme(mode: String) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "cmd uimode night $mode")).use { it.readBytes() }
        }
        try {
            for ((mode, suffix) in listOf(AppCompatDelegate.MODE_NIGHT_NO to "light", AppCompatDelegate.MODE_NIGHT_YES to "dark")) {
                // Portal follows device theme; exercise SystemUI and app together.
                systemTheme(if (mode == AppCompatDelegate.MODE_NIGHT_YES) "yes" else "no")
                instrumentation.runOnMainSync { AppCompatDelegate.setDefaultNightMode(mode) }
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    compose.onNodeWithText("开始模拟").assertIsDisplayed()
                    capture("home-$suffix")
                    scenario.onActivity { activity ->
                        val controller = androidx.core.view.WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                        assertEquals(mode == AppCompatDelegate.MODE_NIGHT_NO, controller.isAppearanceLightStatusBars)
                        assertEquals(mode == AppCompatDelegate.MODE_NIGHT_NO, controller.isAppearanceLightNavigationBars)
                    }
                    for ((destination, name, marker) in listOf(
                        Triple(R.id.nav_mock, "point", "位置名称"),
                        Triple(R.id.nav_settings, "settings", "定位海拔"),
                        Triple(R.id.nav_runtime, "runtime", "打开开发者选项"),
                        Triple(R.id.nav_route_gallery, "library", "搜索资料"),
                        Triple(R.id.nav_gnss_mock, "satellites", "星图信息"))) {
                        scenario.onActivity { it.findNavController(R.id.nav_host_fragment_content_main).navigate(destination) }
                        compose.scrollToText(marker).assertIsDisplayed()
                        capture("$name-$suffix")
                    }
                }
            }
        } finally {
            systemTheme(when (oldSystemMode) { 2 -> "yes"; 0 -> "auto"; else -> "no" })
            instrumentation.runOnMainSync { AppCompatDelegate.setDefaultNightMode(oldMode) }
        }
    }

    @Test fun settingsAndLibraryRemainUsableWithLargeTextAndLandscape() {
        permissions()
        val oldScale = android.provider.Settings.System.getFloat(instrumentation.targetContext.contentResolver, "font_scale", 1f)
        fun fontScale(value: Float) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "settings put system font_scale $value")).use { it.readBytes() }
        }
        try {
            fontScale(1.3f)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.findNavController(R.id.nav_host_fragment_content_main).navigate(R.id.nav_settings) }
                compose.scrollToText("定位精度").assertIsDisplayed()
                capture("settings-large-text")
                scenario.onActivity { it.findNavController(R.id.nav_host_fragment_content_main).navigate(R.id.nav_route_gallery) }
                compose.scrollToText("更多").assertIsDisplayed()
                capture("library-large-text")
                scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                await {
                    var landscape = false
                    scenario.onActivity { landscape = it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
                    landscape
                }
                compose.scrollToText("搜索资料").assertIsDisplayed()
                // Compose uses scrolling rather than the removed XML heading/artwork layout.
                // The search and transfer actions must remain reachable in landscape.
                compose.scrollToText("新建路线").assertIsDisplayed()
                compose.scrollToText("更多").performClick()
                compose.onNodeWithText("导入 JSON").assertIsDisplayed()
                compose.onNodeWithText("取消").performClick()
                capture("library-landscape")
                scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                await {
                    var portrait = false
                    scenario.onActivity { portrait = it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
                    portrait
                }
                compose.scrollToText("搜索资料").assertIsDisplayed()
                compose.scrollToText("更多").assertIsDisplayed()
            }
        } finally { fontScale(oldScale) }
    }

    private fun capture(name: String) {
        captureUi(name)
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 5_000
        while (!predicate()) {
            check(android.os.SystemClock.uptimeMillis() < deadline) { "UI did not settle" }
            Thread.sleep(50)
        }
    }
}
