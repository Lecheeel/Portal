package com.system.location.service.gnss

import android.content.Context
import com.system.location.service.core.gnss.GnssCapture
import com.system.location.service.core.gnss.GnssCaptureStore
import com.system.location.service.core.gnss.GnssCaptureValidator
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Receiver evidence stays local and is excluded from Android backup. */
class GnssCaptureRepository(context: Context) {
    private val store = GnssCaptureStore(File(context.noBackupFilesDir, "gnss/receiver-capture.json"))
    private val rejected = GnssCaptureStore(File(context.noBackupFilesDir, "gnss/latest-rejected-capture.json"))

    suspend fun load(): GnssCapture? = withContext(Dispatchers.IO) { store.load() }

    suspend fun save(capture: GnssCapture) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val quality = GnssCaptureValidator.validate(capture, now)
        if (!quality.eligible) {
            // Keep receiver evidence for diagnosis without replacing the last playback source.
            rejected.save(capture)
            error("采集未达到回放条件：${quality.issues.joinToString("；")}。保留上次画像；本次记录仅保存为本地诊断")
        }
        store.saveForPlayback(capture, now)
    }
}
