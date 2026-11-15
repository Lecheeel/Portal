package com.system.location.service.gnss

import android.content.Context
import com.system.location.service.ext.sharedPrefs
import com.system.location.service.core.gnss.GnssCaptureStore
import com.system.location.service.core.gnss.GnssCaptureValidator
import java.io.File

/** Startup choices are separate from the live module's transient switches. */
var Context.gnssReplayOnStart: Boolean
    get() = sharedPrefs.getBoolean("gnssReplayOnStart", false)
    set(value) { sharedPrefs.edit().putBoolean("gnssReplayOnStart", value).apply() }

val Context.replayEnvironmentName: String
    get() = sharedPrefs.getString("replayEnvironmentName", "").orEmpty()

/** Called off the main thread, including every start, since receiver evidence expires. */
internal fun Context.gnssReplayProblem(): String? {
    if (!gnssReplayOnStart || sharedPrefs.getString("gnssSimulationMode", "AUTONOMOUS") != "REPLAY") return null
    return try {
        val capture = GnssCaptureStore(File(noBackupFilesDir, "gnss/receiver-capture.json")).load()
            ?: return "尚无卫星采集，请先采集或选择目标天空"
        GnssCaptureValidator.validate(capture, System.currentTimeMillis()).takeUnless { it.eligible }
            ?.let { "卫星采集不可用：${it.issues.joinToString("；")}" }
    } catch (_: Exception) { "无法读取卫星采集，请重新采集或选择目标天空" }
}
