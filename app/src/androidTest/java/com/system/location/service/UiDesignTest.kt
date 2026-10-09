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
        try {
            for ((mode, suffix) in listOf(AppCompatDelegate.MODE_NIGHT_NO to "light", AppCompatDelegate.MODE_NIGHT_YES to "dark")) {
                instrumentation.runOnMainSync { AppCompatDelegate.setDefaultNightMode(mode) }
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    capture("home-$suffix")
                    for ((destination, name) in listOf(R.id.nav_mock to "point", R.id.nav_settings to "settings", R.id.nav_runtime to "runtime", R.id.nav_route_gallery to "library")) {
                        scenario.onActivity { it.findNavController(R.id.nav_host_fragment_content_main).navigate(destination) }
                        instrumentation.waitForIdleSync()
                        capture("$name-$suffix")
                    }
                }
            }
        } finally { instrumentation.runOnMainSync { AppCompatDelegate.setDefaultNightMode(oldMode) } }
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
                onView(withId(R.id.library_more)).perform(click())
                onView(withText("导入 JSON")).check(matches(isDisplayed()))
                androidx.test.espresso.Espresso.pressBack()
                capture("library-landscape")
            }
        } finally { fontScale(oldScale) }
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-captures").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 5_000
        while (!predicate()) {
            check(android.os.SystemClock.uptimeMillis() < deadline) { "UI did not settle" }
            Thread.sleep(50)
        }
    }
}
