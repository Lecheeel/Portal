package com.system.location.service

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.view.FrameMetrics
import android.view.Window
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real window frame timings. Vendor map Surface/GPU presentation is outside this measurement. */
internal class UiFrameProbe(activity: Activity) : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val worker = HandlerThread("portal-ui-frame-probe").apply { start() }
    private val handler = Handler(worker.looper)
    private val window = activity.window
    private val refreshRate = activity.windowManager.defaultDisplay.refreshRate.toDouble()
    private val frames = mutableListOf<Pair<Long, Long>>()
    private var droppedReports = 0
    private var firstDraws = 0
    private var attached = true
    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
        droppedReports += dropped
        if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) firstDraws++
        else {
            val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            val deadline = if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.DEADLINE) else 0L
            if (duration > 0) frames += duration to (deadline.takeIf { it > 0 } ?: (1_000_000_000 / refreshRate).toLong())
        }
    }
    init { instrumentation.runOnMainSync { window.addOnFrameMetricsAvailableListener(listener, handler) } }

    fun save(name: String): JSONObject {
        require(name.matches(Regex("[a-z0-9-]+")))
        detach()
        val flushed = CountDownLatch(1)
        handler.post { flushed.countDown() }
        check(flushed.await(5, TimeUnit.SECONDS))
        check(frames.isNotEmpty()) { "No window frames were recorded for $name" }
        val sorted = frames.map { it.first / 1_000_000.0 }.sorted()
        fun percentile(q: Double) = sorted[(kotlin.math.ceil(sorted.size * q).toInt() - 1).coerceAtLeast(0)]
        val report = JSONObject().apply {
            put("workload", name); put("build", "debug"); put("device", Build.MODEL)
            put("android", Build.VERSION.SDK_INT); put("refreshRateAtStartHz", refreshRate)
            put("scope", "app-window; excludes vendor map Surface/GPU presentation; first draws excluded")
            put("frames", frames.size); put("excludedFirstDraws", firstDraws); put("droppedMetricReports", droppedReports)
            put("p50Ms", percentile(.50)); put("p95Ms", percentile(.95)); put("p99Ms", percentile(.99))
            put("maxMs", sorted.last()); put("missedDeadline", frames.count { (duration, deadline) -> duration > deadline })
            put("durationsMs", JSONArray(frames.map { it.first / 1_000_000.0 }))
            put("deadlinesMs", JSONArray(frames.map { it.second / 1_000_000.0 }))
        }
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-frame-stats").apply { check(mkdirs() || isDirectory) }
        val file = File(directory, "$name.json").apply { writeText(report.toString(2)) }
        fun shell(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        shell("mkdir -p /sdcard/Download/Portal-ui-frame-stats")
        shell("cp ${file.absolutePath} /sdcard/Download/Portal-ui-frame-stats/$name.json")
        android.util.Log.i("PortalUiFrames", report.toString())
        return report
    }

    override fun close() {
        try {
            detach()
        } finally {
            worker.quitSafely()
            worker.join(5_000)
            check(!worker.isAlive) { "Frame probe worker did not stop" }
        }
    }

    private fun detach() {
        instrumentation.runOnMainSync {
            if (attached) {
                window.removeOnFrameMetricsAvailableListener(listener)
                attached = false
            }
        }
    }
}
