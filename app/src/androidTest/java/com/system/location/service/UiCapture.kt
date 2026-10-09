package com.system.location.service

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** AGP uninstalls the app after connected tests; preserve captures outside its removed data. */
internal fun captureUi(name: String) {
    require(name.matches(Regex("[a-z0-9-]+")))
    val instrumentation = InstrumentationRegistry.getInstrumentation()
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
