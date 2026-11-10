package com.system.location.service.detector

import android.content.Context
import com.system.location.service.detector.checks.AllChecks
import com.system.location.service.detector.model.CheckContext
import com.system.location.service.detector.model.DetectorReport
import com.system.location.service.detector.model.DeviceInfoCollector
import com.system.location.service.detector.model.GnssEvidenceSnapshot
import com.system.location.service.detector.model.SampleSnapshot
import com.system.location.service.detector.sample.CheckSampler
import com.system.location.service.detector.CheckRunner
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Shared headless runner for the UI and the debug bridge. */
object DetectorRunner {
    private val running = AtomicBoolean(false)

    @Volatile
    var lastReportJson: String? = null
        private set

    @Volatile
    var lastGeneratedAtMillis: Long = 0L
        private set

    val isRunning: Boolean get() = running.get()

    fun run(context: Context, settings: DetectorSettings): DetectorReport? {
        if (!running.compareAndSet(false, true)) return null
        return try {
            val appContext = context.applicationContext
            val sampler = CheckSampler(appContext, settings)
            val snapshot = runCatching { sampler.collect { } }.getOrElse { error ->
                SampleSnapshot(notes = listOf("采样失败：${error.javaClass.simpleName}: ${error.message}"))
            }
            val checkContext = CheckContext(appContext, settings, snapshot)
            val results = CheckRunner.runAll(checkContext, AllChecks.list()) { _, _, _ -> }
            val report = DetectorReport(
                generatedAtMillis = System.currentTimeMillis(),
                device = DeviceInfoCollector.collect(appContext),
                checks = results,
                gnssEvidence = GnssEvidenceSnapshot(snapshot.startedAtMillis, snapshot.endedAtMillis, snapshot.gnssFrames),
            )
            val json = report.toJson()
            lastReportJson = json
            lastGeneratedAtMillis = report.generatedAtMillis
            runCatching {
                File(appContext.getExternalFilesDir(null), "last-report.json").writeText(json)
            }
            report
        } finally {
            running.set(false)
        }
    }
}
