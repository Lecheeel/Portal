package com.system.location.service

import android.Manifest
import android.graphics.Bitmap
import androidx.appcompat.app.AppCompatDelegate
import androidx.navigation.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.core.runtime.RuntimePhase
import com.system.location.service.core.runtime.RuntimeState
import com.system.location.service.core.runtime.SubmissionHealth
import com.system.location.service.ui.displayStates
import com.system.location.service.ui.mock.LibraryAdapter
import com.system.location.service.ui.mock.LibraryRow
import com.system.location.service.ui.viewmodel.LibraryItem
import com.system.location.service.ui.viewmodel.LibraryKind
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import android.widget.TextView

@RunWith(AndroidJUnit4::class)
class UiDesignTest {
    @Test fun homeHasNoToolbarGapAfterReturningFromEveryOtherPage() {
        permissions()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun homeGeometry(): List<Int> {
                onView(withId(R.id.topSearchBarContainer)).check(matches(isDisplayed()))
                var geometry = emptyList<Int>()
                scenario.onActivity { activity ->
                    val map = activity.findViewById<android.view.View>(R.id.amapView)
                    val mapPosition = IntArray(2).also { map.getLocationOnScreen(it) }
                    val rootPosition = IntArray(2).also { activity.binding.drawerLayout.getLocationOnScreen(it) }
                    val search = activity.findViewById<android.view.View>(R.id.topSearchBarContainer)
                    val searchPosition = IntArray(2).also { search.getLocationOnScreen(it) }
                    val bottom = activity.findViewById<android.view.View>(R.id.bottomControlCard)
                    val bottomPosition = IntArray(2).also { bottom.getLocationOnScreen(it) }
                    assertEquals(rootPosition[1], mapPosition[1])
                    assertEquals(activity.binding.drawerLayout.height, map.height)
                    assertEquals(android.view.View.GONE, activity.binding.appBarMain.appBarLayout.visibility)
                    geometry = listOf(mapPosition[1], map.height, searchPosition[1], bottomPosition[1] + bottom.height)
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
                        androidx.test.espresso.Espresso.pressBack()
                    }
                    assertEquals("Home geometry changed after destination $destination (drawer=$useDrawer)", initial, homeGeometry())
                }
            }
            scenario.onActivity { it.findNavController(R.id.nav_host_fragment_content_main).navigate(R.id.nav_mock) }
            onView(withId(R.id.toolbar)).check(matches(isDisplayed()))
            scenario.recreate()
            onView(withId(R.id.toolbar)).check(matches(isDisplayed()))
            androidx.test.espresso.Espresso.pressBack()
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
                onView(withId(R.id.point_name)).perform(scrollTo()).check(matches(isDisplayed()))
                scenario.onActivity {
                    assertEquals(R.id.nav_mock, it.findNavController(R.id.nav_host_fragment_content_main).currentDestination?.id)
                }
            }
            openMock()
            repeat(2) {
                onView(withId(R.id.library)).perform(scrollTo(), click())
                onView(withId(R.id.library_search)).check(matches(isDisplayed()))
                if (it == 0) scenario.recreate()
                openMock()
            }
            onView(withId(R.id.library)).perform(scrollTo(), click())
            androidx.test.espresso.Espresso.pressBack()
            onView(withId(R.id.point_name)).perform(scrollTo()).check(matches(isDisplayed()))
        }
    }

    @Test fun edgeToEdgeKeepsMapFullSizeAndControlsInsideSafeInsets() {
        permissions()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.topSearchBarContainer)).check(matches(isDisplayed()))
            scenario.onActivity { activity ->
                val container = activity.binding.appBarMain.root
                val insets = androidx.core.view.WindowInsetsCompat.Builder()
                    .setInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars(), androidx.core.graphics.Insets.of(12, 48, 16, 32))
                    .build()
                val search = activity.findViewById<android.view.View>(R.id.topSearchBarContainer)
                androidx.core.view.ViewCompat.dispatchApplyWindowInsets(container, insets)
                assertEquals(0, container.paddingTop)
                assertEquals(0, container.paddingBottom)
                assertTrue(search.paddingTop >= 48)
                val appliedTop = search.paddingTop
                androidx.core.view.ViewCompat.dispatchApplyWindowInsets(container, insets)
                assertEquals(appliedTop, search.paddingTop)
                val bottom = activity.findViewById<android.view.View>(R.id.bottomControlCard)
                assertTrue((bottom.layoutParams as android.view.ViewGroup.MarginLayoutParams).bottomMargin >= 32)
                val map = activity.findViewById<android.view.View>(R.id.amapView)
                assertEquals(activity.binding.drawerLayout.height, map.height)
                assertEquals(android.view.View.GONE, activity.binding.appBarMain.appBarLayout.visibility)
                activity.findNavController(R.id.nav_host_fragment_content_main).navigate(R.id.nav_mock)
                androidx.core.view.ViewCompat.dispatchApplyWindowInsets(container, insets)
                assertEquals(48, container.paddingTop)
                assertEquals(32, container.paddingBottom)
                assertEquals(12, container.paddingLeft)
                assertEquals(16, container.paddingRight)
                assertEquals(android.view.View.VISIBLE, activity.binding.appBarMain.toolbar.visibility)
            }
        }
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun permissions() {
        listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, it)
        }
    }

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
            scenario.onActivity { it.findNavController(R.id.nav_host_fragment_content_main).navigate(R.id.nav_route_gallery) }
            onView(withId(R.id.library_search)).perform(replaceText("不会匹配的记录"), closeSoftKeyboard())
            await {
                var text = ""
                scenario.onActivity { text = it.findViewById<TextView>(R.id.empty_title).text.toString() }
                text == instrumentation.targetContext.getString(R.string.ui_library_no_match)
            }
            onView(withId(R.id.empty_title)).check(matches(withText(R.string.ui_library_no_match)))
            onView(withId(R.id.library_search)).perform(replaceText(""), closeSoftKeyboard())
            onView(withId(R.id.kind_locations)).perform(click())
            await {
                var enabled = false
                scenario.onActivity { enabled = it.findViewById<android.view.View>(R.id.library_more).isEnabled }
                enabled
            }
            onView(withId(R.id.library_more)).perform(click())
            onView(withText("导入 JSON")).check(matches(isDisplayed()))
            androidx.test.espresso.Espresso.pressBack()
            capture("library-empty")
        }
    }

    @Test fun largeLibraryUsesRecycledRowsAndKeepsTheSelectedItemIdentity() {
        permissions()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var clicked: String? = null
            lateinit var recycler: RecyclerView
            scenario.onActivity { activity ->
                recycler = RecyclerView(activity).apply {
                    layoutManager = LinearLayoutManager(activity)
                    adapter = LibraryAdapter { clicked = it.id }.apply {
                        submitList((0 until 1000).map { LibraryRow(LibraryKind.ROUTES, LibraryItem("r$it", "路线 $it", "单次 · 120 个点")) })
                    }
                    itemAnimator = null
                }
                activity.setContentView(recycler)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertEquals(1000, recycler.adapter!!.itemCount)
                assertTrue(recycler.childCount in 1..20)
                recycler.findViewHolderForAdapterPosition(0)!!.itemView.performClick()
                assertEquals("r0", clicked)
                recycler.scrollToPosition(999)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                recycler.findViewHolderForAdapterPosition(999)!!.itemView.performClick()
                assertEquals("r999", clicked)
                assertTrue(recycler.childCount < 20)
            }
            capture("library-populated")
        }
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
                    onView(withId(R.id.btn_apply_location)).check(matches(isDisplayed()))
                    capture("home-$suffix")
                    scenario.onActivity { activity ->
                        val controller = androidx.core.view.WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                        assertEquals(mode == AppCompatDelegate.MODE_NIGHT_NO, controller.isAppearanceLightStatusBars)
                        assertEquals(mode == AppCompatDelegate.MODE_NIGHT_NO, controller.isAppearanceLightNavigationBars)
                    }
                    for ((destination, name, marker) in listOf(
                        Triple(R.id.nav_mock, "point", R.id.point_name),
                        Triple(R.id.nav_settings, "settings", R.id.altitude_value),
                        Triple(R.id.nav_runtime, "runtime", R.id.phase_title),
                        Triple(R.id.nav_route_gallery, "library", R.id.library_search),
                        Triple(R.id.nav_gnss_mock, "satellites", R.id.satellite_rada_view))) {
                        scenario.onActivity { it.findNavController(R.id.nav_host_fragment_content_main).navigate(destination) }
                        onView(withId(marker)).check(matches(isDisplayed()))
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
                onView(withId(R.id.accuracy_value)).perform(scrollTo()).check(matches(isDisplayed()))
                capture("settings-large-text")
                scenario.onActivity { it.findNavController(R.id.nav_host_fragment_content_main).navigate(R.id.nav_route_gallery) }
                onView(withId(R.id.library_more)).check(matches(isDisplayed()))
                capture("library-large-text")
                scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                await {
                    var landscape = false
                    scenario.onActivity { landscape = it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
                    landscape
                }
                onView(withId(R.id.library_search)).check(matches(isDisplayed()))
                onView(withId(R.id.library_heading)).check(matches(withEffectiveVisibility(Visibility.GONE)))
                onView(withId(R.id.empty_artwork)).check(matches(withEffectiveVisibility(Visibility.GONE)))
                onView(withId(R.id.library_more)).perform(click())
                onView(withText("导入 JSON")).check(matches(isDisplayed()))
                androidx.test.espresso.Espresso.pressBack()
                capture("library-landscape")
                scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                await {
                    var portrait = false
                    scenario.onActivity { portrait = it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
                    portrait
                }
                onView(withId(R.id.library_heading)).check(matches(isDisplayed()))
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
