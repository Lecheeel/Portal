package io.github.lecheeel.integrity

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.AtomicFile
import androidx.core.content.ContextCompat
import io.github.lecheeel.integrity.core.SessionReport
import kotlinx.serialization.json.Json
import java.io.File

data class CaptureView(val active: Boolean = false, val report: SessionReport? = null,
    val message: String = "准备好开始一次观测", val storageError: String? = null, val saving: Boolean = false)
object CaptureStore {
    @Volatile var view = CaptureView()
        internal set
}
object CapturePermissions {
    fun has(context: Context, permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    fun location(context: Context) = has(context, Manifest.permission.ACCESS_FINE_LOCATION) || has(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    fun snapshot(context: Context) = linkedMapOf(
        "fineLocation" to has(context, Manifest.permission.ACCESS_FINE_LOCATION),
        "coarseLocation" to has(context, Manifest.permission.ACCESS_COARSE_LOCATION),
        "notifications" to (Build.VERSION.SDK_INT < 33 || has(context, Manifest.permission.POST_NOTIFICATIONS)))
}
/** Atomic replacement, bounded history, no cloud backup. Corruption is surfaced to the UI. */
class ReportRepository(context: Context) {
    private val directory = File(context.noBackupFilesDir, "integrity-sessions")
    companion object {
        val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
        private val lock = Any()
        private const val MAX_REPORT_BYTES = 12_000_000L
        fun encode(report: SessionReport) = json.encodeToString(SessionReport.serializer(), report)
    }
    fun save(report: SessionReport) = synchronized(lock) {
        check(report.id.matches(Regex("[a-f0-9-]{36}"))) { "Invalid report id" }
        check(directory.exists() || directory.mkdirs()) { "无法建立实验目录" }
        val target = File(directory, report.id + ".json")
        if (target.exists()) {
            val old = json.decodeFromString(SessionReport.serializer(), AtomicFile(target).openRead().bufferedReader().use { it.readText() })
            if (old.capturedElapsedNanos > report.capturedElapsedNanos || (old.status != "RUNNING" && report.status == "RUNNING")) return@synchronized
        }
        val bytes = encode(report).toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_REPORT_BYTES) { "实验报告超过存储限制" }
        val file = AtomicFile(target)
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
        directory.listFiles { f -> f.extension == "json" }.orEmpty().sortedByDescending { it.lastModified() }
            .drop(20).forEach { check(it.delete()) { "无法清理旧实验" } }
    }
    fun list(): List<SessionReport> = synchronized(lock) {
        directory.listFiles { f -> f.extension == "json" }.orEmpty().sortedByDescending { it.lastModified() }.take(20).map { file ->
            check(file.length() <= MAX_REPORT_BYTES) { "实验报告过大：" + file.name }
            val report = json.decodeFromString(SessionReport.serializer(), AtomicFile(file).openRead().bufferedReader().use { it.readText() })
            if (report.status == "RUNNING" && (!CaptureStore.view.active || CaptureStore.view.report?.id != report.id)) report.copy(status = "INTERRUPTED") else report
        }
    }
}
