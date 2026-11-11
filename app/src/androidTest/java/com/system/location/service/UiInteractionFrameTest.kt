package com.system.location.service

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.GravityCompat
import androidx.navigation.findNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** No Compose test rule: animations use the device's real frame clock. Timings are diagnostic. */
@RunWith(AndroidJUnit4::class)
class UiInteractionFrameTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "UI did not reach the expected state" }
            SystemClock.sleep(50)
        }
    }
    private fun findText(text: String): AccessibilityNodeInfo? {
        val pending = java.util.ArrayDeque<AccessibilityNodeInfo>()
        instrumentation.uiAutomation.rootInActiveWindow?.let(pending::add)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let(pending::add)
        }
        return null
    }
    private fun clickText(text: String) {
        var target: AccessibilityNodeInfo? = null
        await {
            target = findText(text)
            target != null
        }
        var node = checkNotNull(target)
        while (!node.isClickable) node = checkNotNull(node.parent) { "No click action for $text" }
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        SystemClock.sleep(350) // Let the physical transition render on its normal clock.
    }

    @Test fun recordDrawerNavigationAndEditorWindowFrames() {
        acceptSelectedMapForUiTest()
        grantUiPermissions()
        val draft = File(instrumentation.targetContext.filesDir, "route_draft.json")
        val original = draft.takeIf { it.exists() }?.readBytes()
        val methodTrace = InstrumentationRegistry.getArguments().getString("methodTrace") == "true"
        var tracing = false
        fun save(probe: UiFrameProbe, name: String) = probe.save(if (methodTrace) "$name-traced" else name)
        try {
            check(!draft.exists() || draft.delete())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                await { findText("开始模拟") != null }
                SystemClock.sleep(500)
                lateinit var activity: MainActivity
                scenario.onActivity { activity = it }
                var probe = UiFrameProbe(activity)
                probe.use {
                    repeat(4) {
                        scenario.onActivity { it.binding.drawerLayout.openDrawer(GravityCompat.START) }
                        await { var open = false; scenario.onActivity { open = it.binding.drawerLayout.isDrawerOpen(GravityCompat.START) }; open }
                        SystemClock.sleep(350)
                        scenario.onActivity { it.binding.drawerLayout.closeDrawer(GravityCompat.START) }
                        await { var open = true; scenario.onActivity { open = it.binding.drawerLayout.isDrawerVisible(GravityCompat.START) }; !open }
                        SystemClock.sleep(350)
                    }
                    save(probe, "drawer-transitions")
                }
                if (methodTrace) {
                    // Diagnostic mode only: tracing perturbs timings, so reports use separate names.
                    android.os.Debug.startMethodTracingSampling(
                        File(instrumentation.targetContext.cacheDir, "ui-navigation.trace").absolutePath,
                        32 * 1024 * 1024, 1_000)
                    tracing = true
                }
                scenario.onActivity { it.binding.navView.menu.performIdentifierAction(R.id.nav_settings, 0) }
                await { findText("地图与定位") != null }
                SystemClock.sleep(350)
                probe = UiFrameProbe(activity)
                probe.use {
                    repeat(4) {
                        clickText("地图与定位")
                        clickText("定位精度")
                        clickText("取消")
                        scenario.onActivity { it.onSupportNavigateUp() }
                        SystemClock.sleep(350)
                    }
                    save(probe, "settings-navigation")
                }
                probe = UiFrameProbe(activity)
                probe.use {
                    repeat(4) {
                        scenario.onActivity { it.binding.navView.menu.performIdentifierAction(R.id.nav_home, 0) }
                        await { findText("开始模拟") != null }
                        SystemClock.sleep(350)
                        scenario.onActivity { it.binding.navView.menu.performIdentifierAction(R.id.nav_settings, 0) }
                        await { findText("地图与定位") != null }
                        SystemClock.sleep(350)
                    }
                    save(probe, "map-settings-roundtrips")
                }
                scenario.onActivity { it.findNavController(R.id.nav_host_fragment_content_main).navigate(R.id.nav_route_edit) }
                SystemClock.sleep(700)
                probe = UiFrameProbe(activity)
                probe.use {
                    repeat(4) { clickText("画线"); clickText("加点"); clickText("规划") }
                    save(probe, "route-mode-transitions")
                }
                captureUi("route-editor-feedback")
            }
        } finally {
            if (tracing) android.os.Debug.stopMethodTracing()
            if (original == null) check(!draft.exists() || draft.delete()) else draft.writeBytes(original)
        }
    }
}
