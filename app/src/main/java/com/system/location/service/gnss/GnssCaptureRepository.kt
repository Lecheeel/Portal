package com.system.location.service.gnss

import android.content.Context
import com.system.location.service.core.gnss.GnssCapture
import com.system.location.service.core.gnss.GnssCaptureStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Receiver evidence stays local and is excluded from Android backup. */
class GnssCaptureRepository(context: Context) {
    private val store = GnssCaptureStore(File(context.noBackupFilesDir, "gnss/receiver-capture.json"))

    suspend fun load(): GnssCapture? = withContext(Dispatchers.IO) { store.load() }

    suspend fun save(capture: GnssCapture) = withContext(Dispatchers.IO) { store.save(capture) }
}
