package com.system.location.service

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** AGP uninstalls the app after connected tests; preserve captures outside its removed data. */
internal fun captureUi(name: String) {
    require(name.matches(Regex("[a-z0-9-]+")))
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.waitForIdleSync()
    // An idle main queue can still precede rendering: wait for the resumed window's
    // next committed frame, then allow two display frames before reading pixels.
    val frame = CountDownLatch(1)
    instrumentation.runOnMainSync {
        val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single()
        val decor = activity.window.decorView
        check(decor.isHardwareAccelerated) { "Captures require a rendered hardware window" }
        decor.viewTreeObserver.registerFrameCommitCallback { frame.countDown() }
        decor.invalidate()
    }
    check(frame.await(5, TimeUnit.SECONDS)) { "Window did not commit a frame for $name" }
    val presented = CountDownLatch(1)
    instrumentation.runOnMainSync {
        val decor = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single().window.decorView
        decor.postOnAnimation { decor.postOnAnimation { presented.countDown() } }
    }
    check(presented.await(5, TimeUnit.SECONDS)) { "Display frames unavailable for $name" }
    instrumentation.waitForIdleSync()
    val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-captures").apply { check(mkdirs() || isDirectory) }
    val screenshot = File(directory, "$name.png")
    val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Screenshot unavailable" }
    try { screenshot.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
    finally { bitmap.recycle() }
    fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
    shell("mkdir -p /sdcard/Download/Portal-ui-captures")
    shell("cp ${screenshot.absolutePath} /sdcard/Download/Portal-ui-captures/$name.png")
}
