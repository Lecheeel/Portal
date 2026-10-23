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
import org.junit.FixMethodOrder
import org.junit.runners.MethodSorters
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
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
    /** Shell is an external test producer in a disposable emulator, not a permission of this app. */
    @Test fun zPreciseSessionReceivesExternalMockLocation() {
        shell("pm grant " + context.packageName + " android.permission.ACCESS_FINE_LOCATION")
        shell("appops set com.android.shell android:mock_location allow")
        shell("cmd location providers add-test-provider gps")
        try {
            shell("cmd location providers set-test-provider-enabled gps true")
            ActivityScenario.launch(MainActivity::class.java).use {
                onView(withText("开始观测")).perform(click())
                await { CaptureStore.view.active && CaptureStore.view.report?.permissions?.get("fineLocation") == true }
                val deadline = SystemClock.elapsedRealtime() + 12_000
                while (CaptureStore.view.report?.evidence?.none { evidence -> evidence.code == "MOCK_FLAG" } != false && SystemClock.elapsedRealtime() < deadline) {
                    shell("cmd location providers set-test-provider-location gps --location 30.0,120.0 --accuracy 5")
                    SystemClock.sleep(500)
                }
                await { CaptureStore.view.report?.verdict == "MOCK_OBSERVED" }
                assertTrue(CaptureStore.view.report!!.health.liveSamples > 0)
                assertTrue(CaptureStore.view.report!!.samples.any { sample -> sample.isMock && !sample.cached && sample.requestedProvider == "gps" })
                SystemClock.sleep(1200)
                onView(withText("观察到模拟标记")).check(matches(isDisplayed()))
                screenshot("mock-observed")
                onView(withText("停止并保存")).perform(click())
                await { !CaptureStore.view.active && !CaptureStore.view.saving }
                assertEquals("MOCK_OBSERVED", ReportRepository(context).list().first().verdict)
            }
        } finally {
            if (CaptureStore.view.active) { ObservationService.stop(context); await { !CaptureStore.view.active && !CaptureStore.view.saving } }
            shell("cmd location providers remove-test-provider gps")
            shell("appops set com.android.shell android:mock_location default")
        }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue("Timed out waiting for capture state", condition())
        instrumentation.waitForIdleSync()
    }
    private fun shell(command: String) {
        val output = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        check(!output.contains("Error:") && !output.contains("Exception")) { command + ": " + output }
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot capture failed")
        val directory = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(directory, name + ".png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
