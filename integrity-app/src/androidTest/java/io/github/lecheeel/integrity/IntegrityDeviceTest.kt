package io.github.lecheeel.integrity

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class IntegrityDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Test fun manifestDoesNotRequestMockRootNetworkOrBackgroundLocation() {
        val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        assertFalse(permissions.contains(Manifest.permission.INTERNET))
        assertFalse(permissions.contains("android.permission.ACCESS_MOCK_LOCATION"))
        assertFalse(permissions.contains(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        assertFalse(permissions.contains("android.permission.QUERY_ALL_PACKAGES"))
        assertNull(context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData?.get("xposedmodule"))
    }
    @Test fun pagesRenderAndExplainMissingEvidence() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("Location Integrity")).check(matches(isDisplayed()))
            screenshot("overview")
            onView(withText("信号")).perform(click())
            onView(withText("定位来源 · WGS84")).check(matches(isDisplayed()))
            screenshot("signals")
            onView(withText("证据")).perform(click())
            onView(withText("可解释证据")).check(matches(isDisplayed()))
            screenshot("evidence")
            onView(withText("实验")).perform(click())
            screenshot("sessions")
        }
    }
    @Test fun coarsePermissionSessionDegradesGnssAndSaves() {
        shell("pm grant " + context.packageName + " android.permission.ACCESS_COARSE_LOCATION")
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("开始观测")).perform(click())
            await { CaptureStore.view.active && CaptureStore.view.report != null }
            val report = CaptureStore.view.report!!
            assertFalse(report.permissions["fineLocation"]!!)
            assertTrue(report.gnss.statuses.values.all { value -> value.contains("精确定位") })
            assertTrue(report.providerStates.isNotEmpty())
            assertTrue(report.gnss.statuses.isNotEmpty())
            assertFalse(report.evidence.any { evidence -> evidence.level == "DIRECT" })
            SystemClock.sleep(1200)
            screenshot("coarse-observing")
            onView(withText("停止并保存")).perform(click())
            await { !CaptureStore.view.active && !CaptureStore.view.saving }
            assertEquals("STOPPED", CaptureStore.view.report!!.status)
            val saved = ReportRepository(context).list().first { saved -> saved.id == report.id }
            assertEquals("STOPPED", saved.status)
            ReportRepository(context).save(saved.copy(status = "RUNNING", capturedElapsedNanos = saved.capturedElapsedNanos - 1))
            assertEquals("STOPPED", ReportRepository(context).list().first { item -> item.id == saved.id }.status)
            assertEquals(saved, ReportRepository.json.decodeFromString(io.github.lecheeel.integrity.core.SessionReport.serializer(), ReportRepository.encode(saved)))
        }
    }
    @Test fun darkThemeRenders() {
        instrumentation.runOnMainSync { AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                onView(withText("Location Integrity")).check(matches(isDisplayed()))
                screenshot("overview-dark")
            }
        } finally { instrumentation.runOnMainSync { AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) } }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue("Timed out waiting for capture state", condition())
        instrumentation.waitForIdleSync()
    }
    private fun shell(command: String) { android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() } }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot capture failed")
        val directory = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(directory, name + ".png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
